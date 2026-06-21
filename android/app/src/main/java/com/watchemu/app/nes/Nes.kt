package com.watchemu.app.nes

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class Nes {
    lateinit var mapper: Mapper
        private set
    val ppu = Ppu(this)
    val cpu = Cpu(this)
    val apu = Apu()
    val controller1 = Controller()
    val controller2 = Controller()

    private val ram = ByteArray(2048)
    var romLoaded = false
        private set

    var nmiPending = false
    val irqPending: Boolean get() = mapper.irqPending && !cpu.flagI

    val frameBuffer: IntArray get() = ppu.frameBuffer

    fun loadRom(data: ByteArray) {
        val rom = Rom(data)
        mapper = Mapper.create(rom)
        reset()
        romLoaded = true
    }

    fun reset() {
        ram.fill(0)
        ppu.reset()
        cpu.reset()
        apu.reset()
        nmiPending = false
    }

    fun frame() {
        val ppuCyclesPerFrame = 341 * 262
        var ppuCyclesRun = 0
        ppu.frameComplete = false
        while (!ppu.frameComplete) {
            val cpuCycles = cpu.step()
            for (c in 0 until cpuCycles) apu.stepFrameCounter()
            val ppuCycles = cpuCycles * 3
            for (i in 0 until ppuCycles) {
                ppu.step()
                if (ppu.nmiTriggered) {
                    ppu.nmiTriggered = false
                    nmiPending = true
                }
            }
            ppuCyclesRun += ppuCycles
            if (ppuCyclesRun > ppuCyclesPerFrame + 1000) break
        }
    }

    /** Run one CPU instruction + corresponding PPU/APU cycles. Returns CPU cycles consumed. */
    fun stepInstruction(): Int {
        val cpuCycles = cpu.step()
        for (c in 0 until cpuCycles) apu.stepFrameCounter()
        val ppuCycles = cpuCycles * 3
        for (i in 0 until ppuCycles) {
            ppu.step()
            if (ppu.nmiTriggered) {
                ppu.nmiTriggered = false
                nmiPending = true
            }
        }
        return cpuCycles
    }

    fun cpuRead(addr: Int): Int = when {
        addr < 0x2000 -> ram[addr and 0x07FF].toInt() and 0xFF
        addr < 0x4000 -> ppu.readRegister(addr and 7)
        addr == 0x4015 -> apu.readStatus()
        addr == 0x4016 -> controller1.read()
        addr == 0x4017 -> controller2.read()
        addr >= 0x4020 -> mapper.cpuRead(addr)
        else -> 0
    }

    fun cpuWrite(addr: Int, value: Int) {
        when {
            addr < 0x2000 -> ram[addr and 0x07FF] = value.toByte()
            addr < 0x4000 -> ppu.writeRegister(addr and 7, value)
            addr == 0x4014 -> {
                val page = value shl 8
                val data = ByteArray(256) { cpuRead(page + it).toByte() }
                ppu.writeOamDma(data, 0)
                cpu.stallCycles += 513 + (if (cpu.cycles % 2 == 1) 1 else 0)
            }
            addr == 0x4016 -> { controller1.write(value); controller2.write(value) }
            addr in 0x4000..0x4015 || addr == 0x4017 -> apu.writeRegister(addr, value)
            addr >= 0x4020 -> mapper.cpuWrite(addr, value)
        }
    }

    fun buttonDown(button: Int) = controller1.setButton(button, true)
    fun buttonUp(button: Int) = controller1.setButton(button, false)

    fun saveState(): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeInt(0x57454D55) // magic "WEMU"

        // RAM
        out.write(ram)

        // CPU
        out.writeInt(cpu.a); out.writeInt(cpu.x); out.writeInt(cpu.y)
        out.writeInt(cpu.sp); out.writeInt(cpu.pc); out.writeInt(cpu.cycles)
        out.writeInt(cpu.stallCycles)
        out.writeBoolean(cpu.flagC); out.writeBoolean(cpu.flagZ); out.writeBoolean(cpu.flagI)
        out.writeBoolean(cpu.flagD); out.writeBoolean(cpu.flagB)
        out.writeBoolean(cpu.flagV); out.writeBoolean(cpu.flagN)

        // PPU
        ppu.saveState(out)

        // APU
        apu.saveState(out)

        // Mapper
        mapper.saveState(out)

        out.writeBoolean(nmiPending)
        out.flush()
        return bos.toByteArray()
    }

    fun loadState(data: ByteArray) {
        val inp = DataInputStream(ByteArrayInputStream(data))
        val magic = inp.readInt()
        if (magic != 0x57454D55) return

        // RAM
        inp.readFully(ram)

        // CPU
        cpu.a = inp.readInt(); cpu.x = inp.readInt(); cpu.y = inp.readInt()
        cpu.sp = inp.readInt(); cpu.pc = inp.readInt(); cpu.cycles = inp.readInt()
        cpu.stallCycles = inp.readInt()
        cpu.flagC = inp.readBoolean(); cpu.flagZ = inp.readBoolean(); cpu.flagI = inp.readBoolean()
        cpu.flagD = inp.readBoolean(); cpu.flagB = inp.readBoolean()
        cpu.flagV = inp.readBoolean(); cpu.flagN = inp.readBoolean()

        // PPU
        ppu.loadState(inp)

        // APU
        apu.loadState(inp)

        // Mapper
        mapper.loadState(inp)

        nmiPending = inp.readBoolean()
    }
}
