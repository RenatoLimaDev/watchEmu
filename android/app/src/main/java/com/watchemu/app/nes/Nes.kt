package com.watchemu.app.nes

/**
 * Thin Kotlin facade over the native NES core (see [NativeBridge]). Emulation,
 * rendering and audio synthesis all run in C++; this class only holds the Kotlin
 * frame buffer the UI draws and forwards calls across JNI.
 */
class Nes {
    companion object {
        const val WIDTH = 256
        const val HEIGHT = 240
    }

    /** Latest frame as ARGB_8888 pixels, refreshed from native each video frame. */
    val frameBuffer = IntArray(WIDTH * HEIGHT)

    /** Drives emulation from the audio thread and owns audio output. */
    val apu = Apu()

    var romLoaded = false
        private set

    fun loadRom(data: ByteArray) {
        if (!NativeBridge.loadRom(data)) {
            throw IllegalArgumentException("Unsupported or invalid ROM")
        }
        romLoaded = true
    }

    fun buttonDown(button: Int) = NativeBridge.setButton(button, true)
    fun buttonUp(button: Int) = NativeBridge.setButton(button, false)

    fun saveState(): ByteArray = NativeBridge.saveState()
    fun loadState(data: ByteArray) { NativeBridge.loadState(data) }
}
