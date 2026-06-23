package com.watchemu.app

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchemu.app.nes.Controller
import com.watchemu.app.nes.Nes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Classic-Views activity (no Jetpack Compose) so the app installs and runs on
 * old standalone Android watches (Android 4.4/5.1). Two screens: a ROM picker
 * and the [GameView]. The native core (see com.watchemu.app.nes) is unchanged.
 */
class MainActivity : Activity() {

    private val nes = Nes()
    private lateinit var root: FrameLayout
    private var gameView: GameView? = null
    private var inGame = false

    private var romFiles: List<File> = emptyList()
    private var currentRomName: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanRuns = 0

    companion object {
        private const val REQ_DISCOVERABLE = 1001
        private const val REQ_BT_PERMS = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        scanForRoms()
        if (!handleIncomingIntent(intent)) showPicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!inGame) { scanForRoms(); showPicker() }
    }

    override fun onPause() {
        super.onPause()
        if (inGame) saveState()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveState()
        nes.apu.stop()
    }

    override fun onBackPressed() {
        if (inGame) {
            saveState()
            nes.apu.stop()
            inGame = false
            scanForRoms()
            showPicker()
        } else {
            super.onBackPressed()
        }
    }

    // ---- Picker screen ----
    private fun showPicker() {
        inGame = false
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
        }

        col.addView(TextView(this).apply {
            text = "WatchEmu"
            setTextColor(0xFFD4920A.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })

        col.addView(Button(this).apply {
            text = "Receber (Bluetooth)"
            setOnClickListener { startBtReceive() }
        })

        if (romFiles.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Nenhuma ROM encontrada.\nEnvie um .nes por Bluetooth\nou copie para Download."
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        } else {
            for (file in romFiles) {
                col.addView(TextView(this).apply {
                    text = file.nameWithoutExtension
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { loadRomBytes(file.readBytes(), file.nameWithoutExtension) }
                    setOnLongClickListener { confirmDelete(file); true }
                })
            }
        }

        scroll.addView(col)
        root.removeAllViews()
        root.addView(scroll, matchParent())
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun confirmDelete(file: File) {
        // Simplified: tap to open, long-press deletes immediately.
        deleteRom(file)
        scanForRoms()
        showPicker()
    }

    // ---- Game screen ----
    private fun showGame() {
        inGame = true
        val gv = GameView(this)
        gv.input = object : GameView.Input {
            override fun down(button: Int) = nes.buttonDown(button)
            override fun up(button: Int) = nes.buttonUp(button)
        }
        gameView = gv
        root.removeAllViews()
        root.addView(gv, matchParent())
        startGameLoop()
    }

    private fun loadRomBytes(data: ByteArray, name: String) {
        val romData = if (isZip(data)) (extractNesFromZip(data) ?: data) else data
        try {
            nes.loadRom(romData)
            currentRomName = name
            loadSaveState()
            showGame()
        } catch (e: Exception) {
            e.printStackTrace()
            toast("ROM não suportada")
        }
    }

    private fun startGameLoop() {
        nes.apu.stop()
        val gv = gameView ?: return
        nes.apu.startWithEmulation(nes) {
            // Audio thread: native already filled nes.frameBuffer; hand it to the view.
            gv.submitFrame(nes.frameBuffer)
        }
    }

    // ---- Save state ----
    private fun saveState() {
        val name = currentRomName ?: return
        if (!nes.romLoaded) return
        try {
            File(getExternalFilesDir(null), "$name.sav").writeBytes(nes.saveState())
        } catch (_: Exception) {}
    }

    private fun loadSaveState() {
        val name = currentRomName ?: return
        try {
            val f = File(getExternalFilesDir(null), "$name.sav")
            if (f.exists()) nes.loadState(f.readBytes())
        } catch (_: Exception) {}
    }

    // ---- ROM discovery ----
    private fun scanForRoms() {
        val dirs = listOf(
            File(getExternalFilesDir(null), ""),
            File("/sdcard/Download"),
            File("/sdcard/NES"),
            File("/sdcard/bluetooth"),
            File("/sdcard/Bluetooth"),
            File("/sdcard/Android/media")
        )
        val found = mutableListOf<File>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.filter {
                it.extension.equals("nes", true) || it.extension.equals("zip", true)
            }?.let { found.addAll(it) }
        }
        romFiles = found
    }

    private fun deleteRom(file: File) {
        try {
            file.delete()
            File(getExternalFilesDir(null), "${file.nameWithoutExtension}.sav").delete()
        } catch (_: Exception) {}
    }

    // ---- Bluetooth receive (simplified) ----
    private fun startBtReceive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (perms.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                requestPermissions(perms, REQ_BT_PERMS)
                return
            }
        }
        requestDiscoverable()
    }

    private fun requestDiscoverable() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        try {
            startActivityForResult(intent, REQ_DISCOVERABLE)
        } catch (_: Exception) {
            toast("Bluetooth indisponível")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_PERMS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestDiscoverable()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DISCOVERABLE && resultCode > 0) startPeriodicScan()
    }

    private fun startPeriodicScan() {
        scanRuns = 0
        val task = object : Runnable {
            override fun run() {
                scanForRoms()
                if (!inGame) showPicker()
                if (++scanRuns < 60) mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.postDelayed(task, 2000)
    }

    // ---- Incoming ROM intents ----
    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri ?: return false
        return try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return false
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "received.nes"
            val fileName = if (name.endsWith(".nes", true) || name.endsWith(".zip", true)) name else "$name.nes"
            File(getExternalFilesDir(null), fileName).writeBytes(bytes)
            scanForRoms()
            loadRomBytes(bytes, fileName.substringBeforeLast('.'))
            true
        } catch (_: Exception) { false }
    }

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()

    private fun extractNesFromZip(data: ByteArray): ByteArray? {
        try {
            ZipInputStream(data.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".nes", true)) {
                        val bos = ByteArrayOutputStream()
                        zis.copyTo(bos)
                        return bos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
