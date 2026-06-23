package com.watchemu.app.nes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Audio output driver. The native core both emulates and synthesizes audio; this
 * class owns the [AudioTrack] and a high-priority thread that pulls samples from
 * native in real time. Pacing comes from blocking writes to the track, which also
 * paces the emulation (the native core only advances while producing samples).
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
    fun startWithEmulation(nes: Nes, onFrame: () -> Unit) {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufBytes = (minBuf * 2).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(BUFFER_SAMPLES)

            while (running) {
                // Native runs the NES until it has produced one buffer of samples,
                // snapshotting any completed video frame as a side effect.
                val frames = NativeBridge.renderAudio(buf, buf.size)
                if (frames > 0) {
                    NativeBridge.getFrameBuffer(nes.frameBuffer)
                    onFrame()
                }

                try {
                    val written = track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
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
