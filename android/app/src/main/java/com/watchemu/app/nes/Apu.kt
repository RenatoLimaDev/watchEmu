package com.watchemu.app.nes

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Audio output driver. The native core both emulates and synthesizes audio; this
 * class owns the [AudioTrack] and a high-priority thread that pulls 16-bit PCM
 * samples from native in real time. Pacing comes from blocking writes to the
 * track, which also paces the emulation (native only advances while producing
 * samples).
 *
 * Uses the legacy [AudioTrack] constructor + 16-bit PCM so it works back to
 * Android 4.4 (the modern Builder/float APIs are API 21–23).
 */
class Apu {
    companion object {
        const val SAMPLE_RATE = 44100
        private const val BUFFER_SAMPLES = 512
    }

    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false

    /**
     * @param nes      facade whose [Nes.frameBuffer] is refreshed on each frame
     * @param onFrame  called from the audio thread when a video frame completes
     */
    @Suppress("DEPRECATION")
    fun startWithEmulation(nes: Nes, onFrame: () -> Unit) {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = (minBuf * 2).coerceAtLeast(4096)

        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
            AudioTrack.MODE_STREAM
        )

        audioTrack = track
        track.play()

        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(BUFFER_SAMPLES)

            while (running) {
                val frames = NativeBridge.renderAudio(buf, buf.size)
                if (frames > 0) {
                    NativeBridge.getFrameBuffer(nes.frameBuffer)
                    onFrame()
                }

                try {
                    // Blocking write in MODE_STREAM paces us to real time.
                    val written = track.write(buf, 0, buf.size)
                    if (written < 0) Thread.sleep(10)
                } catch (_: Exception) {
                    Thread.sleep(10)
                }
            }
        }, "NES-Audio-Emu").start()
    }

    fun stop() {
        running = false
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null
    }
}
