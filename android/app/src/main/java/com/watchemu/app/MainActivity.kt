package com.watchemu.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import com.watchemu.app.nes.Controller
import com.watchemu.app.nes.Nes
import com.watchemu.app.ui.RomPickerScreen
import com.watchemu.app.ui.WatchScreen
import com.watchemu.app.ui.theme.WatchEmuTheme
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private val nes = Nes()

    // Double-buffered output. The audio thread (which drives emulation) copies
    // the finished frame into [pendingPixels] under [pixelLock]; the main thread
    // then uploads it into whichever bitmap is NOT currently being shown. Handing
    // Compose a different instance each frame means the GPU never reads a bitmap
    // while it is being mutated (no tearing / data race).
    private val gameBitmaps = arrayOf(
        Bitmap.createBitmap(Nes.WIDTH, Nes.HEIGHT, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(Nes.WIDTH, Nes.HEIGHT, Bitmap.Config.ARGB_8888)
    )
    private var gameBitmapIdx = 0
    private val pixelLock = Any()
    private val pendingPixels = IntArray(Nes.WIDTH * Nes.HEIGHT)
    private var pendingFrame = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bitmapState = mutableStateOf<Bitmap?>(null)
    private var romLoadedState = mutableStateOf(false)

    private var currentScreen = mutableStateOf("picker")
    private var romFiles = mutableStateOf<List<File>>(emptyList())
    private var btReceiving = mutableStateOf(false)
    private var scanJob: Job? = null
    private var currentRomName: String? = null

    // Analog stick state (normalized offset from center, -1..1)
    var analogX = mutableFloatStateOf(0f)
    var analogY = mutableFloatStateOf(0f)

    private val activeButtons = mutableMapOf<Int, Int>()
    private var analogPointerId = -1
    private var analogCenterX = 0f
    private var analogCenterY = 0f
    private var analogRadius = 0f
    private var analogDirX = 0
    private var analogDirY = 0

    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) requestDiscoverable()
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode > 0) {
            btReceiving.value = true
            startPeriodicScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        scanForRoms()

        setContent {
            val screen by currentScreen
            val loaded by romLoadedState
            val roms by romFiles
            val stickX by analogX
            val stickY by analogY
            val receiving by btReceiving

            WatchEmuTheme {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        if (targetState == "game") {
                            fadeIn(tween(400)) + scaleIn(
                                initialScale = 0.85f,
                                animationSpec = tween(400)
                            ) togetherWith fadeOut(tween(200))
                        } else {
                            fadeIn(tween(300)) togetherWith
                                fadeOut(tween(200)) + scaleOut(
                                    targetScale = 0.85f,
                                    animationSpec = tween(200)
                                )
                        }
                    },
                    label = "screen"
                ) { targetScreen ->
                    when (targetScreen) {
                        "picker" -> RomPickerScreen(
                            romFiles = roms,
                            isReceiving = receiving,
                            onReceive = { startBtReceive() },
                            onRomSelected = { file -> loadRomBytes(file.readBytes(), file.nameWithoutExtension) },
                            onRomDeleted = { file -> deleteRom(file) }
                        )
                        "game" -> {
                            BackHandler {
                                saveState()
                                nes.apu.stop()
                                romLoadedState.value = false
                                bitmapState.value = null
                                currentScreen.value = "picker"
                            }
                            WatchScreen(
                                bitmap = bitmapState,
                                romLoaded = loaded,
                                stickOffsetX = stickX,
                                stickOffsetY = stickY
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanForRoms()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        scanJob?.cancel()
        nes.apu.stop()
    }

    private fun startBtReceive() {
        if (btReceiving.value) {
            btReceiving.value = false
            scanJob?.cancel()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                btPermissionLauncher.launch(perms)
                return
            }
        }
        requestDiscoverable()
    }

    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        }
        discoverableLauncher.launch(intent)
    }

    private fun startPeriodicScan() {
        scanJob?.cancel()
        scanJob = CoroutineScope(Dispatchers.Main).launch {
            repeat(60) {
                delay(2000)
                scanForRoms()
            }
            btReceiving.value = false
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (currentScreen.value != "game") return super.dispatchTouchEvent(event)

        val w = window.decorView.width.toFloat()
        val h = window.decorView.height.toFloat()
        val diameter = minOf(w, h)
        val ox = (w - diameter) / 2f
        val oy = (h - diameter) / 2f

        // Analog stick geometry (matches WatchScreen drawing)
        val dpadCx = ox + diameter * 0.5f
        val dpadCy = oy + diameter * 0.91f
        val dpadR = diameter * 0.09f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val ex = event.getX(idx)
                val ey = event.getY(idx)

                // Check if touch is on analog stick
                val adx = ex - dpadCx
                val ady = ey - dpadCy
                if (sqrt(adx * adx + ady * ady) <= dpadR * 1.3f) {
                    analogPointerId = id
                    analogCenterX = dpadCx
                    analogCenterY = dpadCy
                    analogRadius = dpadR
                    updateAnalog(ex, ey)
                } else {
                    val px = (ex - ox) / diameter
                    val py = (ey - oy) / diameter
                    val btn = hitTest(px, py)
                    if (btn >= 0 && nes.romLoaded) {
                        activeButtons[id] = btn
                        nes.buttonDown(btn)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until event.pointerCount) {
                    val id = event.getPointerId(idx)
                    val ex = event.getX(idx)
                    val ey = event.getY(idx)

                    if (id == analogPointerId) {
                        updateAnalog(ex, ey)
                    } else {
                        val px = (ex - ox) / diameter
                        val py = (ey - oy) / diameter
                        val btn = hitTest(px, py)
                        val prev = activeButtons[id]
                        if (prev != null && prev != btn) {
                            nes.buttonUp(prev)
                            if (btn >= 0) {
                                activeButtons[id] = btn
                                nes.buttonDown(btn)
                            } else {
                                activeButtons.remove(id)
                            }
                        } else if (prev == null && btn >= 0 && nes.romLoaded) {
                            activeButtons[id] = btn
                            nes.buttonDown(btn)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == analogPointerId) {
                    releaseAnalog()
                } else {
                    val prev = activeButtons.remove(id)
                    if (prev != null) nes.buttonUp(prev)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAnalog()
                for ((_, btn) in activeButtons) nes.buttonUp(btn)
                activeButtons.clear()
            }
        }
        return true
    }

    private fun updateAnalog(ex: Float, ey: Float) {
        var dx = ex - analogCenterX
        var dy = ey - analogCenterY
        val dist = sqrt(dx * dx + dy * dy)
        val maxDist = analogRadius * 0.8f
        if (dist > maxDist) {
            dx = dx / dist * maxDist
            dy = dy / dist * maxDist
        }

        analogX.floatValue = (dx / maxDist).coerceIn(-1f, 1f)
        analogY.floatValue = (dy / maxDist).coerceIn(-1f, 1f)

        val deadzone = 0.25f
        val norm = (dist / maxDist).coerceAtMost(1f)

        val newDirX: Int
        val newDirY: Int
        if (norm > deadzone) {
            val angle = atan2(dy, dx)
            val cosA = cos(angle)
            val sinA = sin(angle)
            newDirX = if (cosA > 0.4f) 1 else if (cosA < -0.4f) -1 else 0
            newDirY = if (sinA > 0.4f) 1 else if (sinA < -0.4f) -1 else 0
        } else {
            newDirX = 0
            newDirY = 0
        }

        if (newDirX != analogDirX) {
            if (analogDirX == -1) nes.buttonUp(Controller.BUTTON_LEFT)
            if (analogDirX == 1) nes.buttonUp(Controller.BUTTON_RIGHT)
            analogDirX = newDirX
            if (analogDirX == -1) nes.buttonDown(Controller.BUTTON_LEFT)
            if (analogDirX == 1) nes.buttonDown(Controller.BUTTON_RIGHT)
        }
        if (newDirY != analogDirY) {
            if (analogDirY == -1) nes.buttonUp(Controller.BUTTON_UP)
            if (analogDirY == 1) nes.buttonUp(Controller.BUTTON_DOWN)
            analogDirY = newDirY
            if (analogDirY == -1) nes.buttonDown(Controller.BUTTON_UP)
            if (analogDirY == 1) nes.buttonDown(Controller.BUTTON_DOWN)
        }
    }

    private fun releaseAnalog() {
        analogPointerId = -1
        analogX.floatValue = 0f
        analogY.floatValue = 0f
        if (analogDirX == -1) nes.buttonUp(Controller.BUTTON_LEFT)
        if (analogDirX == 1) nes.buttonUp(Controller.BUTTON_RIGHT)
        if (analogDirY == -1) nes.buttonUp(Controller.BUTTON_UP)
        if (analogDirY == 1) nes.buttonUp(Controller.BUTTON_DOWN)
        analogDirX = 0
        analogDirY = 0
    }

    private fun hitTest(px: Float, py: Float): Int {
        val cx = px - 0.5f
        val cy = py - 0.5f
        val dist = sqrt(cx * cx + cy * cy)
        if (dist > 0.5f) return -1

        if (py < 0.15f && px > 0.08f && px < 0.50f) return Controller.BUTTON_A
        if (py < 0.15f && px > 0.50f && px < 0.92f) return Controller.BUTTON_B
        if (px < 0.13f && py > 0.10f && py < 0.90f) return Controller.BUTTON_SELECT
        if (px > 0.87f && py > 0.10f && py < 0.90f) return Controller.BUTTON_START

        return -1
    }

    private fun loadRomBytes(data: ByteArray, name: String = "game") {
        val romData = if (isZip(data)) extractNesFromZip(data) ?: data else data
        try {
            nes.loadRom(romData)
            currentRomName = name
            // Try to restore previous save state
            loadSaveState()
            romLoadedState.value = true
            currentScreen.value = "game"
            startGameLoop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveState() {
        val name = currentRomName ?: return
        if (!nes.romLoaded) return
        try {
            val stateData = nes.saveState()
            val saveFile = File(getExternalFilesDir(null), "$name.sav")
            saveFile.writeBytes(stateData)
        } catch (_: Exception) { }
    }

    private fun loadSaveState() {
        val name = currentRomName ?: return
        try {
            val saveFile = File(getExternalFilesDir(null), "$name.sav")
            if (saveFile.exists()) {
                nes.loadState(saveFile.readBytes())
            }
        } catch (_: Exception) { }
    }

    private fun startGameLoop() {
        nes.apu.stop()
        // Audio-driven emulation: the audio thread runs the NES CPU/PPU,
        // producing frames as a side effect of generating audio samples.
        // This ensures audio is never starved and game speed is correct.
        nes.apu.startWithEmulation(nes) {
            // Called from the audio thread when a video frame completes. Snapshot
            // the frame buffer under the lock so the main thread can upload it
            // without racing the next frame the emulator is about to produce.
            synchronized(pixelLock) {
                System.arraycopy(nes.frameBuffer, 0, pendingPixels, 0, pendingPixels.size)
                pendingFrame = true
            }
            mainHandler.post {
                val bmp = synchronized(pixelLock) {
                    if (!pendingFrame) return@post
                    pendingFrame = false
                    gameBitmapIdx = gameBitmapIdx xor 1
                    val target = gameBitmaps[gameBitmapIdx]
                    target.setPixels(pendingPixels, 0, Nes.WIDTH, 0, 0, Nes.WIDTH, Nes.HEIGHT)
                    target
                }
                bitmapState.value = bmp
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
            // Save to app storage with original filename
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "received.nes"
            val fileName = if (name.endsWith(".nes", true) || name.endsWith(".zip", true)) name else "$name.nes"
            val dest = File(getExternalFilesDir(null), fileName)
            dest.writeBytes(bytes)
            scanForRoms()
            val romName = fileName.substringBeforeLast('.')
            loadRomBytes(bytes, romName)
        } catch (_: Exception) { }
    }

    private fun deleteRom(file: File) {
        try {
            file.delete()
            // Also delete save state if exists
            val savFile = File(getExternalFilesDir(null), "${file.nameWithoutExtension}.sav")
            savFile.delete()
        } catch (_: Exception) { }
        scanForRoms()
    }

    private fun scanForRoms() {
        val dirs = listOf(
            File(getExternalFilesDir(null), ""),
            File("/sdcard/Download"),
            File("/sdcard/NES"),
            File("/sdcard/bluetooth"),
            File("/sdcard/Bluetooth"),
            File("/sdcard/Android/media"),
        )
        val found = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter {
                it.extension.equals("nes", true) || it.extension.equals("zip", true)
            }?.let { found.addAll(it) }
        }
        romFiles.value = found
    }

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()

    private fun extractNesFromZip(data: ByteArray): ByteArray? {
        try {
            ZipInputStream(data.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".nes", ignoreCase = true)) {
                        val bos = ByteArrayOutputStream()
                        zis.copyTo(bos)
                        return bos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) { }
        return null
    }
}
