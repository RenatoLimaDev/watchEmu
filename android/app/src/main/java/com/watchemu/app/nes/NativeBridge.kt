package com.watchemu.app.nes

/**
 * JNI surface to the native (C++) NES core in libwatchemu.so. A single native
 * machine instance is shared across the process. The audio thread drives the
 * emulation by repeatedly calling [renderAudio]; completed video frames are read
 * back with [getFrameBuffer].
 */
object NativeBridge {
    init { System.loadLibrary("watchemu") }

    external fun loadRom(data: ByteArray): Boolean
    external fun reset()
    external fun setButton(button: Int, pressed: Boolean)
    external fun isRomLoaded(): Boolean

    /** Generate [count] audio samples into [out]; returns video frames completed. */
    external fun renderAudio(out: FloatArray, count: Int): Int

    /** Copy the most recently completed frame (ARGB_8888) into [dst]. */
    external fun getFrameBuffer(dst: IntArray)

    external fun saveState(): ByteArray
    external fun loadState(data: ByteArray): Boolean
}
