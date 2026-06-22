package com.watchemu.app.nes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.DataInputStream
import java.io.DataOutputStream

class Apu {

    companion object {
        const val SAMPLE_RATE = 44100
        private const val CPU_FREQ = 1789773.0
        private const val CYCLES_PER_SAMPLE = CPU_FREQ / SAMPLE_RATE // ~40.58

        private val LENGTH_TABLE = intArrayOf(
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
        )

        private val NOISE_PERIOD = intArrayOf(
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
        )

        private val DUTY = arrayOf(
            floatArrayOf(-1f, 1f, -1f, -1f, -1f, -1f, -1f, -1f),
            floatArrayOf(-1f, 1f, 1f, -1f, -1f, -1f, -1f, -1f),
            floatArrayOf(-1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f),
            floatArrayOf(1f, -1f, -1f, 1f, 1f, 1f, 1f, 1f)
        )

        // NES non-linear mixer lookup tables (hardware-accurate)
        private val PULSE_TABLE = FloatArray(31) { n ->
            if (n == 0) 0f else 95.52f / (8128f / n + 100f)
        }
        private val TND_TABLE = FloatArray(203) { n ->
            if (n == 0) 0f else 163.67f / (24329f / n + 100f)
        }
    }

    // Pulse 1 registers
    var p1Duty = 0; var p1Vol = 0; var p1Period = 0; var p1Len = 0
    var p1Halt = false; var p1Const = false
    var p1EnvD = 0; var p1EnvC = 0; var p1EnvS = false
    var p1SwEn = false; var p1SwP = 0; var p1SwN = false; var p1SwS = 0; var p1SwR = false; var p1SwC = 0

    // Pulse 2 registers
    var p2Duty = 0; var p2Vol = 0; var p2Period = 0; var p2Len = 0
    var p2Halt = false; var p2Const = false
    var p2EnvD = 0; var p2EnvC = 0; var p2EnvS = false
    var p2SwEn = false; var p2SwP = 0; var p2SwN = false; var p2SwS = 0; var p2SwR = false; var p2SwC = 0

    // Triangle registers
    var triPeriod = 0; var triLen = 0; var triLin = 0
    var triCtrl = false; var triLinLoad = 0; var triLinR = false

    // Noise registers
    var noVol = 0; var noPeriod = 4; var noLen = 0; var noMode = false
    var noHalt = false; var noConst = false
    var noEnvD = 0; var noEnvC = 0; var noEnvS = false

    // Enable flags
    var enP1 = false; var enP2 = false; var enT = false; var enN = false

    // Frame counter
    var fcMode = 0; var fcStep = 0
    private var frameAccum = 0.0

    // Synthesis phase accumulators
    private var p1Phase = 0.0
    private var p2Phase = 0.0
    private var triPhase = 0.0
    private var noPhase = 0.0
    private var noShift = 1

    // Filters
    private var hpAccum1 = 0f
    private var hpAccum2 = 0f
    private var lpAccum = 0f

    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    private var emuThread: Thread? = null

    fun reset() {
        p1Duty = 0; p1Vol = 0; p1Period = 0; p1Len = 0; p1Halt = false; p1Const = false
        p1EnvD = 0; p1EnvC = 0; p1EnvS = false
        p1SwEn = false; p1SwP = 0; p1SwN = false; p1SwS = 0; p1SwR = false; p1SwC = 0
        p2Duty = 0; p2Vol = 0; p2Period = 0; p2Len = 0; p2Halt = false; p2Const = false
        p2EnvD = 0; p2EnvC = 0; p2EnvS = false
        p2SwEn = false; p2SwP = 0; p2SwN = false; p2SwS = 0; p2SwR = false; p2SwC = 0
        triPeriod = 0; triLen = 0; triLin = 0; triCtrl = false; triLinLoad = 0; triLinR = false
        noVol = 0; noPeriod = 4; noLen = 0; noMode = false; noHalt = false; noConst = false
        noEnvD = 0; noEnvC = 0; noEnvS = false
        enP1 = false; enP2 = false; enT = false; enN = false
        fcMode = 0; fcStep = 0; frameAccum = 0.0
        p1Phase = 0.0; p2Phase = 0.0; triPhase = 0.0; noPhase = 0.0; noShift = 1
        hpAccum1 = 0f; hpAccum2 = 0f; lpAccum = 0f
    }

    fun stepFrameCounter() {
        frameAccum++
        if (frameAccum < 7457.5) return
        frameAccum = 0.0

        if (fcMode == 0) {
            when (fcStep % 4) {
                0 -> { envAll() }
                1 -> { envAll(); lenAll(); sweepAll() }
                2 -> { envAll() }
                3 -> { envAll(); lenAll(); sweepAll() }
            }
            fcStep++
        } else {
            when (fcStep % 5) {
                0 -> { envAll() }
                1 -> { envAll(); lenAll(); sweepAll() }
                2 -> { envAll() }
                3 -> {}
                4 -> { envAll(); lenAll(); sweepAll() }
            }
            fcStep++
        }
    }

    private fun envAll() {
        if (p1EnvS) { p1EnvS = false; p1EnvD = 15; p1EnvC = p1Vol }
        else if (p1EnvC > 0) p1EnvC--
        else { p1EnvC = p1Vol; if (p1EnvD > 0) p1EnvD-- else if (p1Halt) p1EnvD = 15 }

        if (p2EnvS) { p2EnvS = false; p2EnvD = 15; p2EnvC = p2Vol }
        else if (p2EnvC > 0) p2EnvC--
        else { p2EnvC = p2Vol; if (p2EnvD > 0) p2EnvD-- else if (p2Halt) p2EnvD = 15 }

        if (noEnvS) { noEnvS = false; noEnvD = 15; noEnvC = noVol }
        else if (noEnvC > 0) noEnvC--
        else { noEnvC = noVol; if (noEnvD > 0) noEnvD-- else if (noHalt) noEnvD = 15 }

        if (triLinR) triLin = triLinLoad
        else if (triLin > 0) triLin--
        if (!triCtrl) triLinR = false
    }

    private fun lenAll() {
        if (!p1Halt && p1Len > 0) p1Len--
        if (!p2Halt && p2Len > 0) p2Len--
        if (!triCtrl && triLen > 0) triLen--
        if (!noHalt && noLen > 0) noLen--
    }

    private fun sweepAll() {
        if (p1SwR || p1SwC == 0) {
            if (p1SwEn && p1SwS > 0) { val d = p1Period shr p1SwS; p1Period += if (p1SwN) -(d + 1) else d }
            p1SwC = p1SwP + 1; p1SwR = false
        } else p1SwC--

        if (p2SwR || p2SwC == 0) {
            if (p2SwEn && p2SwS > 0) { val d = p2Period shr p2SwS; p2Period += if (p2SwN) -d else d }
            p2SwC = p2SwP + 1; p2SwR = false
        } else p2SwC--
    }

    fun writeRegister(addr: Int, v: Int) {
        when (addr) {
            0x4000 -> { p1Duty = (v shr 6) and 3; p1Halt = v and 0x20 != 0; p1Const = v and 0x10 != 0; p1Vol = v and 0xF }
            0x4001 -> { p1SwEn = v and 0x80 != 0; p1SwP = (v shr 4) and 7; p1SwN = v and 8 != 0; p1SwS = v and 7; p1SwR = true }
            0x4002 -> p1Period = (p1Period and 0x700) or v
            0x4003 -> if (enP1) { p1Period = (p1Period and 0xFF) or ((v and 7) shl 8); p1Len = LENGTH_TABLE[(v shr 3) and 0x1F]; p1Phase = 0.0; p1EnvS = true }
            0x4004 -> { p2Duty = (v shr 6) and 3; p2Halt = v and 0x20 != 0; p2Const = v and 0x10 != 0; p2Vol = v and 0xF }
            0x4005 -> { p2SwEn = v and 0x80 != 0; p2SwP = (v shr 4) and 7; p2SwN = v and 8 != 0; p2SwS = v and 7; p2SwR = true }
            0x4006 -> p2Period = (p2Period and 0x700) or v
            0x4007 -> if (enP2) { p2Period = (p2Period and 0xFF) or ((v and 7) shl 8); p2Len = LENGTH_TABLE[(v shr 3) and 0x1F]; p2Phase = 0.0; p2EnvS = true }
            0x4008 -> { triCtrl = v and 0x80 != 0; triLinLoad = v and 0x7F }
            0x400A -> triPeriod = (triPeriod and 0x700) or v
            0x400B -> if (enT) { triPeriod = (triPeriod and 0xFF) or ((v and 7) shl 8); triLen = LENGTH_TABLE[(v shr 3) and 0x1F]; triLinR = true }
            0x400C -> { noHalt = v and 0x20 != 0; noConst = v and 0x10 != 0; noVol = v and 0xF }
            0x400E -> { noMode = v and 0x80 != 0; noPeriod = NOISE_PERIOD[v and 0xF] }
            0x400F -> if (enN) { noLen = LENGTH_TABLE[(v shr 3) and 0x1F]; noEnvS = true }
            0x4010, 0x4011, 0x4012, 0x4013 -> {}
            0x4015 -> {
                enP1 = v and 1 != 0; enP2 = v and 2 != 0; enT = v and 4 != 0; enN = v and 8 != 0
                if (!enP1) p1Len = 0; if (!enP2) p2Len = 0; if (!enT) triLen = 0; if (!enN) noLen = 0
            }
            0x4017 -> { fcMode = (v shr 7) and 1; fcStep = 0; if (fcMode == 1) { envAll(); lenAll(); sweepAll() } }
        }
    }

    fun readStatus(): Int {
        var r = 0
        if (p1Len > 0) r = r or 1
        if (p2Len > 0) r = r or 2
        if (triLen > 0) r = r or 4
        if (noLen > 0) r = r or 8
        return r
    }

    // Legacy start (decoupled audio - not used anymore)
    fun start() {
        // no-op: use startWithEmulation() instead
    }

    /**
     * Audio-driven emulation: the audio thread runs the NES emulation,
     * producing exactly the right number of CPU cycles per audio sample.
     * This guarantees audio is never starved, eliminating stuttering.
     *
     * @param nes The NES instance to step
     * @param onFrame Called from audio thread when a video frame completes
     */
    fun startWithEmulation(nes: Nes, forceSilent: Boolean = false, onFrame: () -> Unit) {
        // Make sure any previous emulation thread is fully dead before starting a
        // new one. Without this, calling stop() then start() in quick succession
        // can leave the old thread alive (it re-reads `running` after the new
        // thread sets it true), stacking multiple emulation threads on the same
        // NES and slowing everything down with each game launch.
        stop()
        running = true

        // The Amazfit Stratos has no speaker and a non-standard audio HAL, so
        // creating or starting the AudioTrack may throw. Build it defensively:
        // any failure (or forceSilent) drops us into a silent, wall-clock paced
        // loop so the game still runs instead of never starting.
        val track: AudioTrack? = if (forceSilent) null else run {
            var t: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                )
                val bufBytes = (minBuf * 2).coerceAtLeast(4096)
                t = AudioTrack.Builder()
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
                t.play()
                t
            } catch (_: Throwable) {
                try { t?.release() } catch (_: Throwable) {}
                null
            }
        }

        if (track == null) {
            startSilentEmulation(nes, onFrame)
            return
        }

        audioTrack = track

        emuThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(512)
            var cycleAccum = 0.0
            val bufTimeNs = (1_000_000_000L * buf.size / SAMPLE_RATE).toLong()

            while (running) {
                val startNs = System.nanoTime()

                // Generate one buffer of audio samples by running the emulation
                for (i in 0 until buf.size) {
                    // Run CPU instructions until we've accumulated enough cycles for one sample.
                    // The guard caps how many instructions a single sample may run: if the CPU
                    // ever wedges (e.g. an interrupt storm that never advances PC), this keeps the
                    // audio thread from spinning forever and freezing the picture.
                    var guard = 0
                    while (cycleAccum < CYCLES_PER_SAMPLE && guard < 1000) {
                        guard++
                        val wasComplete = nes.ppu.frameComplete
                        val cycles = nes.stepInstruction()
                        cycleAccum += cycles

                        // Check if a video frame just completed
                        if (nes.ppu.frameComplete && !wasComplete) {
                            nes.ppu.frameComplete = false
                            onFrame()
                        }
                    }
                    cycleAccum -= CYCLES_PER_SAMPLE

                    // Mix one audio sample from the current APU state
                    buf[i] = mixOneSample()
                }

                // Try to write audio; if it fails, fall back to manual timing
                var audioOk = false
                try {
                    val written = track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                    audioOk = written > 0
                } catch (_: Exception) { }

                // If audio write didn't block (failed), pace manually
                if (!audioOk) {
                    val elapsed = System.nanoTime() - startNs
                    val sleepMs = (bufTimeNs - elapsed) / 1_000_000
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
            }
        }, "NES-Audio-Emu").also { it.start() }
    }

    /**
     * Audio-free emulation paced by the wall clock (~60 fps). Used when the
     * device has no usable audio output (e.g. the Amazfit Stratos with no
     * headphones connected, or a firmware whose AudioTrack stalls), so the game
     * still runs — silently — instead of freezing. The APU state still advances
     * inside nes.frame(), so audio resumes correctly if restarted with sound.
     */
    private fun startSilentEmulation(nes: Nes, onFrame: () -> Unit) {
        emuThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
            val frameNs = 1_000_000_000L / 60L
            while (running) {
                val startNs = System.nanoTime()
                nes.frame()
                onFrame()
                val sleepMs = (frameNs - (System.nanoTime() - startNs)) / 1_000_000
                if (sleepMs > 0) try { Thread.sleep(sleepMs) } catch (_: InterruptedException) {}
            }
        }, "NES-Silent-Emu").also { it.start() }
    }

    fun stop() {
        running = false
        // Wait for the emulation thread to actually exit so it can't keep
        // stepping the NES after we return (avoids stacked/ghost emu threads).
        emuThread?.let { t ->
            if (t != Thread.currentThread()) {
                try { t.join(500) } catch (_: InterruptedException) {}
            }
        }
        emuThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioTrack = null
    }

    /** Mix one audio sample from current APU channel state. */
    private fun mixOneSample(): Float {
        // --- Pulse 1 ---
        var p1Out = 0
        if (enP1 && p1Len > 0 && p1Period in 8..0x7FF) {
            val freq = CPU_FREQ / (16.0 * (p1Period + 1))
            p1Phase += freq / SAMPLE_RATE
            if (p1Phase >= 1.0) p1Phase -= 1.0
            val step = (p1Phase * 8).toInt() and 7
            val vol = if (p1Const) p1Vol else p1EnvD
            if (DUTY[p1Duty][step] > 0f) p1Out = vol
        }

        // --- Pulse 2 ---
        var p2Out = 0
        if (enP2 && p2Len > 0 && p2Period in 8..0x7FF) {
            val freq = CPU_FREQ / (16.0 * (p2Period + 1))
            p2Phase += freq / SAMPLE_RATE
            if (p2Phase >= 1.0) p2Phase -= 1.0
            val step = (p2Phase * 8).toInt() and 7
            val vol = if (p2Const) p2Vol else p2EnvD
            if (DUTY[p2Duty][step] > 0f) p2Out = vol
        }

        // --- Triangle ---
        var triOut = 0
        if (enT && triLen > 0 && triLin > 0 && triPeriod >= 2) {
            val freq = CPU_FREQ / (32.0 * (triPeriod + 1))
            triPhase += freq / SAMPLE_RATE
            if (triPhase >= 1.0) triPhase -= 1.0
            val step = (triPhase * 32).toInt() and 31
            triOut = if (step < 16) 15 - step else step - 16
        }

        // --- Noise ---
        var noOut = 0
        if (enN && noLen > 0) {
            val freq = CPU_FREQ / (2.0 * noPeriod)
            noPhase += freq / SAMPLE_RATE
            while (noPhase >= 1.0) {
                noPhase -= 1.0
                val bit = if (noMode) 6 else 1
                val fb = (noShift xor (noShift shr bit)) and 1
                noShift = (noShift shr 1) or (fb shl 14)
            }
            val vol = if (noConst) noVol else noEnvD
            if (noShift and 1 == 0) noOut = vol
        }

        // NES non-linear mixer (hardware-accurate)
        val pulseIdx = (p1Out + p2Out).coerceIn(0, 30)
        val tndIdx = (3 * triOut + 2 * noOut).coerceIn(0, 202)
        var sample = PULSE_TABLE[pulseIdx] + TND_TABLE[tndIdx]

        // DC offset removal
        sample = sample * 2f - 1f

        // High-pass filter 1 (~37 Hz)
        val hp1Alpha = 0.99654f
        hpAccum1 = hp1Alpha * hpAccum1 + sample
        sample = sample - hpAccum1 * (1f - hp1Alpha)

        // High-pass filter 2 (~186 Hz)
        val hp2Alpha = 0.97345f
        val prevHp2 = hpAccum2
        hpAccum2 = hp2Alpha * hpAccum2 + sample
        sample = sample - prevHp2 * (1f - hp2Alpha)

        // Low-pass filter (~14 kHz)
        val lpAlpha = 0.815f
        lpAccum += lpAlpha * (sample - lpAccum)
        sample = lpAccum

        return (sample * 0.7f).coerceIn(-1f, 1f)
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(p1Duty); out.writeInt(p1Vol); out.writeInt(p1Period); out.writeInt(p1Len)
        out.writeBoolean(p1Halt); out.writeBoolean(p1Const)
        out.writeInt(p1EnvD); out.writeInt(p1EnvC); out.writeBoolean(p1EnvS)
        out.writeBoolean(p1SwEn); out.writeInt(p1SwP); out.writeBoolean(p1SwN); out.writeInt(p1SwS); out.writeBoolean(p1SwR); out.writeInt(p1SwC)

        out.writeInt(p2Duty); out.writeInt(p2Vol); out.writeInt(p2Period); out.writeInt(p2Len)
        out.writeBoolean(p2Halt); out.writeBoolean(p2Const)
        out.writeInt(p2EnvD); out.writeInt(p2EnvC); out.writeBoolean(p2EnvS)
        out.writeBoolean(p2SwEn); out.writeInt(p2SwP); out.writeBoolean(p2SwN); out.writeInt(p2SwS); out.writeBoolean(p2SwR); out.writeInt(p2SwC)

        out.writeInt(triPeriod); out.writeInt(triLen); out.writeInt(triLin)
        out.writeBoolean(triCtrl); out.writeInt(triLinLoad); out.writeBoolean(triLinR)

        out.writeInt(noVol); out.writeInt(noPeriod); out.writeInt(noLen); out.writeBoolean(noMode)
        out.writeBoolean(noHalt); out.writeBoolean(noConst)
        out.writeInt(noEnvD); out.writeInt(noEnvC); out.writeBoolean(noEnvS)

        out.writeBoolean(enP1); out.writeBoolean(enP2); out.writeBoolean(enT); out.writeBoolean(enN)
        out.writeInt(fcMode); out.writeInt(fcStep); out.writeDouble(frameAccum)
    }

    fun loadState(inp: DataInputStream) {
        p1Duty = inp.readInt(); p1Vol = inp.readInt(); p1Period = inp.readInt(); p1Len = inp.readInt()
        p1Halt = inp.readBoolean(); p1Const = inp.readBoolean()
        p1EnvD = inp.readInt(); p1EnvC = inp.readInt(); p1EnvS = inp.readBoolean()
        p1SwEn = inp.readBoolean(); p1SwP = inp.readInt(); p1SwN = inp.readBoolean(); p1SwS = inp.readInt(); p1SwR = inp.readBoolean(); p1SwC = inp.readInt()

        p2Duty = inp.readInt(); p2Vol = inp.readInt(); p2Period = inp.readInt(); p2Len = inp.readInt()
        p2Halt = inp.readBoolean(); p2Const = inp.readBoolean()
        p2EnvD = inp.readInt(); p2EnvC = inp.readInt(); p2EnvS = inp.readBoolean()
        p2SwEn = inp.readBoolean(); p2SwP = inp.readInt(); p2SwN = inp.readBoolean(); p2SwS = inp.readInt(); p2SwR = inp.readBoolean(); p2SwC = inp.readInt()

        triPeriod = inp.readInt(); triLen = inp.readInt(); triLin = inp.readInt()
        triCtrl = inp.readBoolean(); triLinLoad = inp.readInt(); triLinR = inp.readBoolean()

        noVol = inp.readInt(); noPeriod = inp.readInt(); noLen = inp.readInt(); noMode = inp.readBoolean()
        noHalt = inp.readBoolean(); noConst = inp.readBoolean()
        noEnvD = inp.readInt(); noEnvC = inp.readInt(); noEnvS = inp.readBoolean()

        enP1 = inp.readBoolean(); enP2 = inp.readBoolean(); enT = inp.readBoolean(); enN = inp.readBoolean()
        fcMode = inp.readInt(); fcStep = inp.readInt(); frameAccum = inp.readDouble()
    }
}
