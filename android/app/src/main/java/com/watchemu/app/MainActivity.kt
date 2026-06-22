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
import androidx.compose.runtime.mutableIntStateOf
import com.watchemu.app.nes.Controller
import com.watchemu.app.nes.Nes
import com.watchemu.app.nes.Ppu
import com.watchemu.app.ui.RomPickerScreen
import com.watchemu.app.ui.SaveChoiceDialog
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
    private var gameBitmap = Bitmap.createBitmap(Ppu.WIDTH, Ppu.HEIGHT, Bitmap.Config.ARGB_8888)
    private var romLoadedState = mutableStateOf(false)
    // The in-game TextureView, captured when the game screen is created. Frames
    // are pushed straight to it from the emulation thread (no per-frame Compose).
    @Volatile private var gameSurface: com.watchemu.app.ui.GameView? = null
    // Watchdog state: timestamp (ns) of the last produced frame, and whether we
    // already restarted emulation in silent (no-audio) mode after a stall.
    @Volatile private var lastFrameNs = 0L
    private var silentMode = false
    private var watchdogJob: Job? = null

    private var currentScreen = mutableStateOf("picker")
    private var romFiles = mutableStateOf<List<File>>(emptyList())
    private var btReceiving = mutableStateOf(false)
    private var scanJob: Job? = null
    private var currentRomName: String? = null
    // A ROM file waiting for the user to pick Continue / New game (only set when
    // a save exists for it). Holds the File so we can load it after the choice.
    private var pendingRom = mutableStateOf<File?>(null)

    // --- Floating d-pad overlay state (read by WatchScreen to draw it) ---
    // Active only while a finger is down on the left half; position is in screen
    // pixels (the touch point), so the d-pad appears under the finger.
    var dpadActive = mutableStateOf(false)
    var dpadCx = mutableFloatStateOf(0f)
    var dpadCy = mutableFloatStateOf(0f)
    var dpadKnobX = mutableFloatStateOf(0f)   // knob offset from center, -1..1
    var dpadKnobY = mutableFloatStateOf(0f)
    // A/B button overlay state (right half).
    var btnAActive = mutableStateOf(false)
    var btnBActive = mutableStateOf(false)
    // Start/Select corner overlay state (top corners).
    var btnStartActive = mutableStateOf(false)
    var btnSelectActive = mutableStateOf(false)
    // Controls are invisible by default; a long-press on the center reveals all
    // button areas for a moment so the player can recall the layout.
    var showAreas = mutableStateOf(false)
    private var longPressJob: Job? = null
    private var hideAreasJob: Job? = null

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
            val roms by romFiles
            val receiving by btReceiving
            // Floating d-pad overlay state
            val dpadOn by dpadActive
            val dpadX by dpadCx
            val dpadY by dpadCy
            val knobX by dpadKnobX
            val knobY by dpadKnobY
            val areasVisible by showAreas
            val romAwaitingChoice by pendingRom

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
                            onRomSelected = { file ->
                                // If a save exists, ask Continue/New game; otherwise just start.
                                if (hasSaveState(file.nameWithoutExtension)) {
                                    pendingRom.value = file
                                } else {
                                    loadRomBytes(file.readBytes(), file.nameWithoutExtension, loadSave = false)
                                }
                            },
                            onRomDeleted = { file -> deleteRom(file) }
                        )
                        "game" -> {
                            BackHandler {
                                saveState()
                                stopEmulation()
                                romLoadedState.value = false
                                currentScreen.value = "picker"
                                // Back to the menu: let the screen sleep normally again.
                                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                            WatchScreen(
                                onSurfaceCreated = { gameSurface = it },
                                dpadActive = dpadOn,
                                dpadCx = dpadX,
                                dpadCy = dpadY,
                                dpadKnobX = knobX,
                                dpadKnobY = knobY,
                                showAreas = areasVisible
                            )
                        }
                    }
                }

                // Save choice overlay: shown only when a save exists for the
                // tapped ROM. Continue loads it; New game ignores it.
                romAwaitingChoice?.let { file ->
                    BackHandler { pendingRom.value = null }
                    SaveChoiceDialog(
                        gameName = file.nameWithoutExtension,
                        onContinue = {
                            pendingRom.value = null
                            loadRomBytes(file.readBytes(), file.nameWithoutExtension, loadSave = true)
                        },
                        onNewGame = {
                            pendingRom.value = null
                            loadRomBytes(file.readBytes(), file.nameWithoutExtension, loadSave = false)
                        },
                        onDismiss = { pendingRom.value = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanForRoms()
        // Resume emulation if we were in a game when the app went to background.
        // Preserve silent mode so a device with no audio doesn't freeze for the
        // watchdog timeout again on every resume.
        if (currentScreen.value == "game" && nes.romLoaded) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startGameLoop(silent = silentMode)
        }
    }

    override fun onPause() {
        super.onPause()
        // App is leaving the foreground: persist progress and stop the emulation
        // thread so it doesn't keep burning CPU/battery while not visible.
        if (nes.romLoaded) saveState()
        stopEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        scanJob?.cancel()
        stopEmulation()
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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                handleTouchDown(id, event.getX(idx), event.getY(idx), w, diameter)
            }
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until event.pointerCount) {
                    val id = event.getPointerId(idx)
                    val ex = event.getX(idx)
                    val ey = event.getY(idx)
                    if (id == analogPointerId) {
                        updateAnalog(ex, ey)
                    } else {
                        // A finger in the top half may slide between slices.
                        val btn = radialButtonAt(ex, ey, w, h)
                        val prev = activeButtons[id]
                        if (prev != btn) {
                            if (prev != null) { nes.buttonUp(prev); setAbOverlay(prev, false) }
                            if (btn >= 0) { activeButtons[id] = btn; nes.buttonDown(btn); setAbOverlay(btn, true) }
                            else activeButtons.remove(id)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                // Finger lifted before 500ms -> it was a tap, not a long-press.
                longPressJob?.cancel()
                if (id == analogPointerId) {
                    releaseAnalog()
                } else {
                    val prev = activeButtons.remove(id)
                    if (prev != null) { nes.buttonUp(prev); setAbOverlay(prev, false) }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                releaseAnalog()
                for ((_, btn) in activeButtons) { nes.buttonUp(btn); setAbOverlay(btn, false) }
                activeButtons.clear()
            }
        }
        return true
    }

    /** Route a new finger: bottom half drives the floating d-pad, top half the action buttons. */
    private fun handleTouchDown(id: Int, ex: Float, ey: Float, w: Float, diameter: Float) {
        // Center neutral zone: a long press there reveals all button areas for a
        // moment so the player can recall the (invisible) layout.
        val h = window.decorView.height.toFloat()
        if (ex in w * 0.42f..w * 0.58f && ey in h * 0.34f..h * 0.66f) {
            longPressJob?.cancel()
            longPressJob = CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                revealAreas()
            }
            return
        }

        // Radial "pizza" layout: bottom half = d-pad; top half is split into four
        // 45-degree slices: SELECT, A, B, START (left -> right).
        if (ey >= h * 0.5f) {
            // Spawn the floating d-pad under the finger (one pointer at a time).
            if (analogPointerId == -1) {
                analogPointerId = id
                analogCenterX = ex
                analogCenterY = ey
                // Larger touch radius than the Wear build: the Stratos screen is
                // only 320x300, so a bigger d-pad is easier to steer with a thumb.
                analogRadius = diameter * 0.13f
                dpadActive.value = true
                dpadCx.floatValue = ex
                dpadCy.floatValue = ey
                updateAnalog(ex, ey)
            }
        } else {
            val btn = radialButtonAt(ex, ey, w, h)
            if (btn >= 0 && nes.romLoaded) {
                activeButtons[id] = btn
                nes.buttonDown(btn)
                setAbOverlay(btn, true)
            }
        }
    }

    /** Maps a point in the TOP half to one of four pizza slices by its angle from
     *  the screen center: SELECT (far left), START, B, A (far right). */
    private fun radialButtonAt(ex: Float, ey: Float, w: Float, h: Float): Int {
        if (ey >= h * 0.5f) return -1
        // atan2 with screen y-down: top = -90deg, left = +/-180deg, right = 0deg.
        val ang = Math.toDegrees(atan2((ey - h * 0.5f).toDouble(), (ex - w * 0.5f).toDouble()))
        // Top semicircle spans -180..0; cut into four 45-degree slices.
        return when {
            ang < -135.0 -> Controller.BUTTON_SELECT  // far left
            ang < -90.0  -> Controller.BUTTON_START   // center-left
            ang < -45.0  -> Controller.BUTTON_B       // center-right
            else         -> Controller.BUTTON_A       // far right (-45..0)
        }
    }

    /** Reveal all button areas for a short while, then fade them back to invisible. */
    private fun revealAreas() {
        showAreas.value = true
        hideAreasJob?.cancel()
        hideAreasJob = CoroutineScope(Dispatchers.Main).launch {
            delay(2500)
            showAreas.value = false
        }
    }

    private fun setAbOverlay(button: Int, on: Boolean) {
        when (button) {
            Controller.BUTTON_A -> btnAActive.value = on
            Controller.BUTTON_B -> btnBActive.value = on
            Controller.BUTTON_START -> btnStartActive.value = on
            Controller.BUTTON_SELECT -> btnSelectActive.value = on
        }
    }

    /** Map a physical key to a NES button, or -1 if it isn't a game key.
     *  Covers a keyboard/gamepad (arrows/Z/X/Enter/Shift, gamepad buttons) and
     *  the Amazfit Stratos side buttons, which on most firmwares emit
     *  DPAD_CENTER and the volume keys. BACK is deliberately left unmapped so it
     *  still exits the game; unknown codes are logged in onKeyDown so the exact
     *  Stratos mapping can be confirmed on the device. */
    private fun nesButtonForKey(keyCode: Int): Int = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> Controller.BUTTON_UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> Controller.BUTTON_DOWN
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> Controller.BUTTON_LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> Controller.BUTTON_RIGHT
        android.view.KeyEvent.KEYCODE_Z,
        android.view.KeyEvent.KEYCODE_BUTTON_A,
        android.view.KeyEvent.KEYCODE_VOLUME_UP -> Controller.BUTTON_A
        android.view.KeyEvent.KEYCODE_X,
        android.view.KeyEvent.KEYCODE_BUTTON_B,
        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> Controller.BUTTON_B
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER -> Controller.BUTTON_START
        android.view.KeyEvent.KEYCODE_SHIFT_RIGHT,
        android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> Controller.BUTTON_SELECT
        else -> -1
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (currentScreen.value == "game" && nes.romLoaded) {
            val btn = nesButtonForKey(keyCode)
            if (btn >= 0) {
                if (event.repeatCount == 0) nes.buttonDown(btn)
                return true
            }
            // Surface any unrecognised key (except Back) so the Stratos side
            // buttons can be identified via `adb logcat -s WatchEmu`.
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) {
                android.util.Log.i(
                    "WatchEmu",
                    "Unmapped keyCode=$keyCode (${android.view.KeyEvent.keyCodeToString(keyCode)})"
                )
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (currentScreen.value == "game" && nes.romLoaded) {
            val btn = nesButtonForKey(keyCode)
            if (btn >= 0) {
                nes.buttonUp(btn)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
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

        dpadKnobX.floatValue = (dx / maxDist).coerceIn(-1f, 1f)
        dpadKnobY.floatValue = (dy / maxDist).coerceIn(-1f, 1f)

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
        dpadActive.value = false
        dpadKnobX.floatValue = 0f
        dpadKnobY.floatValue = 0f
        if (analogDirX == -1) nes.buttonUp(Controller.BUTTON_LEFT)
        if (analogDirX == 1) nes.buttonUp(Controller.BUTTON_RIGHT)
        if (analogDirY == -1) nes.buttonUp(Controller.BUTTON_UP)
        if (analogDirY == 1) nes.buttonUp(Controller.BUTTON_DOWN)
        analogDirX = 0
        analogDirY = 0
    }

    /** True if a save state file exists for this ROM name. */
    private fun hasSaveState(name: String): Boolean =
        File(getExternalFilesDir(null), "$name.sav").exists()

    private fun loadRomBytes(data: ByteArray, name: String = "game", loadSave: Boolean = false) {
        val romData = if (isZip(data)) extractNesFromZip(data) ?: data else data
        try {
            nes.loadRom(romData)
            currentRomName = name
            // Only restore the previous save when the user chose "Continue".
            if (loadSave) loadSaveState()
            romLoadedState.value = true
            currentScreen.value = "game"
            // Keep the screen awake while playing — Wear OS would otherwise dim
            // into ambient after a few seconds, pausing the game ("exits by itself").
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun startGameLoop(silent: Boolean = false) {
        silentMode = silent
        lastFrameNs = System.nanoTime()
        // Audio-driven emulation: the emulation thread runs the NES CPU/PPU,
        // producing frames as a side effect of generating audio samples, so game
        // speed stays correct. If audio is unavailable (silent=true, or the
        // AudioTrack fails inside the APU), it falls back to a wall-clock loop.
        nes.apu.startWithEmulation(nes, forceSilent = silent) {
            // Called from the emulation thread when a video frame completes.
            // The TextureView can be drawn from any thread, so the frame is
            // pushed straight to it — no per-frame Compose work.
            gameBitmap.setPixels(
                nes.frameBuffer, 0, Ppu.WIDTH,
                0, 0, Ppu.WIDTH, Ppu.HEIGHT
            )
            gameSurface?.submitFrame(gameBitmap)
            lastFrameNs = System.nanoTime()
        }
        startWatchdog()
    }

    /** If the audio-driven loop stalls (e.g. the Stratos AudioTrack blocks with
     *  no real output sink), no frame is produced. Detect that and restart
     *  emulation in silent mode so the game keeps running instead of freezing. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(700)
                if (currentScreen.value != "game" || !nes.romLoaded || silentMode) continue
                if (System.nanoTime() - lastFrameNs > 1_500_000_000L) {
                    startGameLoop(silent = true)
                    return@launch
                }
            }
        }
    }

    /** Stop the emulation thread and the watchdog together. */
    private fun stopEmulation() {
        watchdogJob?.cancel()
        watchdogJob = null
        nes.apu.stop()
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
