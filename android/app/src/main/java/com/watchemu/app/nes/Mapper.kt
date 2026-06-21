package com.watchemu.app.nes

import java.io.DataInputStream
import java.io.DataOutputStream

abstract class Mapper(protected val rom: Rom) {
    var mirroring = rom.mirroring
    var irqPending = false

    abstract fun cpuRead(addr: Int): Int
    abstract fun cpuWrite(addr: Int, value: Int)
    abstract fun chrRead(addr: Int): Int
    abstract fun chrWrite(addr: Int, value: Int)
    open fun step(scanline: Int, cycle: Int) {}
    open fun saveState(out: DataOutputStream) { out.writeInt(mirroring); out.writeBoolean(irqPending) }
    open fun loadState(inp: DataInputStream) { mirroring = inp.readInt(); irqPending = inp.readBoolean() }

    companion object {
        fun create(rom: Rom): Mapper = when (rom.mapperNumber) {
            0 -> Mapper0(rom)
            1 -> Mapper1(rom)
            2 -> Mapper2(rom)
            3 -> Mapper3(rom)
            4 -> Mapper4(rom)
            else -> throw UnsupportedOperationException("Mapper ${rom.mapperNumber} not supported")
        }
    }
}

/** NROM — no bank switching */
class Mapper0(rom: Rom) : Mapper(rom) {
    private val prgMask = rom.prgRom.size - 1

    override fun cpuRead(addr: Int): Int {
        if (addr >= 0x8000) return rom.prgRom[addr and prgMask].toInt() and 0xFF
        return 0
    }

    override fun cpuWrite(addr: Int, value: Int) {}

    override fun chrRead(addr: Int): Int = rom.chrRom[addr and 0x1FFF].toInt() and 0xFF

    override fun chrWrite(addr: Int, value: Int) {
        if (rom.hasChrRam) rom.chrRom[addr and 0x1FFF] = value.toByte()
    }

    override fun saveState(out: DataOutputStream) {
        super.saveState(out)
        if (rom.hasChrRam) out.write(rom.chrRom)
    }

    override fun loadState(inp: DataInputStream) {
        super.loadState(inp)
        if (rom.hasChrRam) inp.readFully(rom.chrRom)
    }
}

/** MMC1 / SxROM */
class Mapper1(rom: Rom) : Mapper(rom) {
    private var shiftReg = 0x10
    private var control = 0x0C
    private var chrBank0 = 0
    private var chrBank1 = 0
    private var prgBank = 0
    private val prgRam = ByteArray(8192)

    private fun prgBankOffset(bank: Int): Int {
        val b = bank % (rom.prgRom.size / 0x4000)
        return b * 0x4000
    }

    override fun cpuRead(addr: Int): Int = when {
        addr < 0x6000 -> 0
        addr < 0x8000 -> prgRam[addr - 0x6000].toInt() and 0xFF
        addr < 0xC000 -> {
            val mode = (control shr 2) and 3
            val offset = when (mode) {
                0, 1 -> prgBankOffset(prgBank and 0x0E)
                2 -> 0
                3 -> prgBankOffset(prgBank and 0x0F)
                else -> 0
            }
            rom.prgRom[offset + (addr - 0x8000)].toInt() and 0xFF
        }
        else -> {
            val mode = (control shr 2) and 3
            val offset = when (mode) {
                0, 1 -> prgBankOffset((prgBank and 0x0E) or 1)
                2 -> prgBankOffset(prgBank and 0x0F)
                3 -> prgBankOffset(rom.prgRom.size / 0x4000 - 1)
                else -> 0
            }
            rom.prgRom[offset + (addr - 0xC000)].toInt() and 0xFF
        }
    }

    override fun cpuWrite(addr: Int, value: Int) {
        when {
            addr < 0x6000 -> {}
            addr < 0x8000 -> prgRam[addr - 0x6000] = value.toByte()
            else -> {
                if (value and 0x80 != 0) {
                    shiftReg = 0x10
                    control = control or 0x0C
                    return
                }
                val complete = shiftReg and 1 == 1
                shiftReg = shiftReg shr 1
                shiftReg = shiftReg or ((value and 1) shl 4)
                if (complete) {
                    val reg = (addr shr 13) and 3
                    when (reg) {
                        0 -> {
                            control = shiftReg
                            mirroring = when (control and 3) {
                                2 -> Rom.MIRROR_VERTICAL
                                3 -> Rom.MIRROR_HORIZONTAL
                                else -> control and 3
                            }
                        }
                        1 -> chrBank0 = shiftReg
                        2 -> chrBank1 = shiftReg
                        3 -> prgBank = shiftReg and 0x0F
                    }
                    shiftReg = 0x10
                }
            }
        }
    }

    override fun chrRead(addr: Int): Int {
        val chrSize = rom.chrRom.size
        if (chrSize == 0) return 0
        val mode4k = control and 0x10 != 0
        val bank: Int
        val offset: Int
        if (!mode4k) {
            bank = (chrBank0 and 0x1E) * 0x1000
            offset = bank + addr
        } else if (addr < 0x1000) {
            bank = chrBank0 * 0x1000
            offset = bank + addr
        } else {
            bank = chrBank1 * 0x1000
            offset = bank + (addr - 0x1000)
        }
        return rom.chrRom[offset % chrSize].toInt() and 0xFF
    }

    override fun chrWrite(addr: Int, value: Int) {
        if (rom.hasChrRam) rom.chrRom[addr and 0x1FFF] = value.toByte()
    }

    override fun saveState(out: DataOutputStream) {
        super.saveState(out)
        out.write(prgRam)
        out.writeInt(shiftReg); out.writeInt(control)
        out.writeInt(chrBank0); out.writeInt(chrBank1); out.writeInt(prgBank)
        if (rom.hasChrRam) out.write(rom.chrRom)
    }

    override fun loadState(inp: DataInputStream) {
        super.loadState(inp)
        inp.readFully(prgRam)
        shiftReg = inp.readInt(); control = inp.readInt()
        chrBank0 = inp.readInt(); chrBank1 = inp.readInt(); prgBank = inp.readInt()
        if (rom.hasChrRam) inp.readFully(rom.chrRom)
    }
}

/** UxROM */
class Mapper2(rom: Rom) : Mapper(rom) {
    private var prgBank = 0
    private val lastBank = (rom.prgRom.size / 0x4000) - 1

    override fun cpuRead(addr: Int): Int = when {
        addr < 0x8000 -> 0
        addr < 0xC000 -> rom.prgRom[prgBank * 0x4000 + (addr - 0x8000)].toInt() and 0xFF
        else -> rom.prgRom[lastBank * 0x4000 + (addr - 0xC000)].toInt() and 0xFF
    }

    override fun cpuWrite(addr: Int, value: Int) {
        if (addr >= 0x8000) prgBank = value % (rom.prgRom.size / 0x4000)
    }

    override fun chrRead(addr: Int): Int = rom.chrRom[addr and 0x1FFF].toInt() and 0xFF
    override fun chrWrite(addr: Int, value: Int) {
        if (rom.hasChrRam) rom.chrRom[addr and 0x1FFF] = value.toByte()
    }

    override fun saveState(out: DataOutputStream) {
        super.saveState(out)
        out.writeInt(prgBank)
        if (rom.hasChrRam) out.write(rom.chrRom)
    }

    override fun loadState(inp: DataInputStream) {
        super.loadState(inp)
        prgBank = inp.readInt()
        if (rom.hasChrRam) inp.readFully(rom.chrRom)
    }
}

/** CNROM */
class Mapper3(rom: Rom) : Mapper(rom) {
    private var chrBank = 0
    private val prgMask = rom.prgRom.size - 1

    override fun cpuRead(addr: Int): Int {
        if (addr >= 0x8000) return rom.prgRom[addr and prgMask].toInt() and 0xFF
        return 0
    }

    override fun cpuWrite(addr: Int, value: Int) {
        if (addr >= 0x8000) chrBank = value and 3
    }

    override fun chrRead(addr: Int): Int {
        val offset = chrBank * 0x2000 + addr
        return rom.chrRom[offset % rom.chrRom.size].toInt() and 0xFF
    }

    override fun chrWrite(addr: Int, value: Int) {
        if (rom.hasChrRam) rom.chrRom[addr and 0x1FFF] = value.toByte()
    }

    override fun saveState(out: DataOutputStream) {
        super.saveState(out)
        out.writeInt(chrBank)
        if (rom.hasChrRam) out.write(rom.chrRom)
    }

    override fun loadState(inp: DataInputStream) {
        super.loadState(inp)
        chrBank = inp.readInt()
        if (rom.hasChrRam) inp.readFully(rom.chrRom)
    }
}

/** MMC3 / TxROM */
class Mapper4(rom: Rom) : Mapper(rom) {
    private val prgRam = ByteArray(8192)
    private val regs = IntArray(8)
    private var bankSelect = 0
    private var irqCounter = 0
    private var irqLatch = 0
    private var irqEnabled = false
    private var irqReload = false
    private var prgMode = 0
    private var chrMode = 0
    private val prgBankCount = rom.prgRom.size / 0x2000

    override fun cpuRead(addr: Int): Int = when {
        addr < 0x6000 -> 0
        addr < 0x8000 -> prgRam[addr - 0x6000].toInt() and 0xFF
        addr < 0xA000 -> {
            val bank = if (prgMode == 0) regs[6] % prgBankCount else (prgBankCount - 2)
            rom.prgRom[bank * 0x2000 + (addr - 0x8000)].toInt() and 0xFF
        }
        addr < 0xC000 -> {
            val bank = regs[7] % prgBankCount
            rom.prgRom[bank * 0x2000 + (addr - 0xA000)].toInt() and 0xFF
        }
        addr < 0xE000 -> {
            val bank = if (prgMode == 0) (prgBankCount - 2) else regs[6] % prgBankCount
            rom.prgRom[bank * 0x2000 + (addr - 0xC000)].toInt() and 0xFF
        }
        else -> {
            val bank = (prgBankCount - 1)
            rom.prgRom[bank * 0x2000 + (addr - 0xE000)].toInt() and 0xFF
        }
    }

    override fun cpuWrite(addr: Int, value: Int) {
        when {
            addr < 0x6000 -> {}
            addr < 0x8000 -> prgRam[addr - 0x6000] = value.toByte()
            addr < 0xA000 -> {
                if (addr and 1 == 0) {
                    bankSelect = value and 7
                    prgMode = (value shr 6) and 1
                    chrMode = (value shr 7) and 1
                } else {
                    regs[bankSelect] = value
                }
            }
            addr < 0xC000 -> {
                if (addr and 1 == 0) {
                    mirroring = if (value and 1 == 0) Rom.MIRROR_VERTICAL else Rom.MIRROR_HORIZONTAL
                }
            }
            addr < 0xE000 -> {
                if (addr and 1 == 0) irqLatch = value
                else { irqReload = true }
            }
            else -> {
                if (addr and 1 == 0) { irqEnabled = false; irqPending = false }
                else irqEnabled = true
            }
        }
    }

    override fun chrRead(addr: Int): Int {
        val chrSize = rom.chrRom.size
        if (chrSize == 0) return 0
        val bank = chrBankFor(addr)
        return rom.chrRom[(bank * 0x0400 + (addr and 0x03FF)) % chrSize].toInt() and 0xFF
    }

    override fun chrWrite(addr: Int, value: Int) {
        if (rom.hasChrRam) rom.chrRom[addr and 0x1FFF] = value.toByte()
    }

    private fun chrBankFor(addr: Int): Int {
        val slot = addr / 0x0400
        return if (chrMode == 0) when (slot) {
            0 -> regs[0] and 0xFE
            1 -> regs[0] or 1
            2 -> regs[1] and 0xFE
            3 -> regs[1] or 1
            4 -> regs[2]
            5 -> regs[3]
            6 -> regs[4]
            7 -> regs[5]
            else -> 0
        } else when (slot) {
            0 -> regs[2]
            1 -> regs[3]
            2 -> regs[4]
            3 -> regs[5]
            4 -> regs[0] and 0xFE
            5 -> regs[0] or 1
            6 -> regs[1] and 0xFE
            7 -> regs[1] or 1
            else -> 0
        }
    }

    override fun step(scanline: Int, cycle: Int) {
        // MMC3 scanline counter is clocked by the A12 rising edge during pattern
        // fetches: once per visible scanline (and the pre-render line 261) at
        // cycle 260. It only runs while rendering produces those fetches.
        if (cycle != 260) return
        if (scanline > 239 && scanline != 261) return

        // Reload when zero or when a reload was requested ($C001); otherwise
        // decrement. The IRQ fires ONLY on the non-zero -> zero transition
        // (Sharp/MMC3B behaviour, as used by SMB3). Firing while the counter
        // merely *equals* zero would re-assert the IRQ every scanline whenever
        // the latch is 0, hanging the CPU in an interrupt storm.
        if (irqReload || irqCounter == 0) {
            irqCounter = irqLatch
            irqReload = false
        } else {
            irqCounter--
            if (irqCounter == 0 && irqEnabled) {
                irqPending = true
            }
        }
    }

    override fun saveState(out: DataOutputStream) {
        super.saveState(out)
        out.write(prgRam)
        for (r in regs) out.writeInt(r)
        out.writeInt(bankSelect); out.writeInt(irqCounter); out.writeInt(irqLatch)
        out.writeBoolean(irqEnabled); out.writeBoolean(irqReload)
        out.writeInt(prgMode); out.writeInt(chrMode)
        if (rom.hasChrRam) out.write(rom.chrRom)
    }

    override fun loadState(inp: DataInputStream) {
        super.loadState(inp)
        inp.readFully(prgRam)
        for (i in regs.indices) regs[i] = inp.readInt()
        bankSelect = inp.readInt(); irqCounter = inp.readInt(); irqLatch = inp.readInt()
        irqEnabled = inp.readBoolean(); irqReload = inp.readBoolean()
        prgMode = inp.readInt(); chrMode = inp.readInt()
        if (rom.hasChrRam) inp.readFully(rom.chrRom)
    }
}
