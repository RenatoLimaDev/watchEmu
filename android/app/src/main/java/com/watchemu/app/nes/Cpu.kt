package com.watchemu.app.nes

class Cpu(private val nes: Nes) {
    var a = 0; var x = 0; var y = 0
    var sp = 0xFD; var pc = 0
    var cycles = 0

    // Status flags
    var flagC = false; var flagZ = false; var flagI = true; var flagD = false
    var flagB = false; var flagV = false; var flagN = false

    var stallCycles = 0

    // Set by indexed addressing modes (abx/aby/izy) to report a page boundary
    // crossing. Stored in a field instead of returning a Pair to avoid a heap
    // allocation on every indexed instruction (a hot-path GC pressure source).
    private var pageCrossed = false

    fun reset() {
        a = 0; x = 0; y = 0; sp = 0xFD
        pc = read16(0xFFFC)
        flagI = true; flagC = false; flagZ = false; flagD = false
        flagB = false; flagV = false; flagN = false
        stallCycles = 0
    }

    fun step(): Int {
        if (stallCycles > 0) { stallCycles--; return 1 }

        val startCycles = cycles

        if (nes.nmiPending) {
            nes.nmiPending = false
            nmi()
            return cycles - startCycles
        }
        if (nes.irqPending && !flagI) {
            irq()
            return cycles - startCycles
        }

        val opcode = read(pc); pc = (pc + 1) and 0xFFFF
        execute(opcode)
        return cycles - startCycles
    }

    // -- Memory access --
    private fun read(addr: Int): Int = nes.cpuRead(addr and 0xFFFF)
    private fun write(addr: Int, value: Int) = nes.cpuWrite(addr and 0xFFFF, value)
    private fun read16(addr: Int): Int = read(addr) or (read(addr + 1) shl 8)
    private fun read16Bug(addr: Int): Int {
        val lo = read(addr)
        val hi = read((addr and 0xFF00) or ((addr + 1) and 0xFF))
        return lo or (hi shl 8)
    }

    // -- Stack --
    private fun push(value: Int) { write(0x100 + sp, value); sp = (sp - 1) and 0xFF }
    private fun push16(value: Int) { push((value shr 8) and 0xFF); push(value and 0xFF) }
    private fun pull(): Int { sp = (sp + 1) and 0xFF; return read(0x100 + sp) }
    private fun pull16(): Int { val lo = pull(); return lo or (pull() shl 8) }

    // -- Flags --
    private fun packStatus(brk: Boolean = false): Int {
        var s = 0x20 // bit 5 always set
        if (flagC) s = s or 0x01
        if (flagZ) s = s or 0x02
        if (flagI) s = s or 0x04
        if (flagD) s = s or 0x08
        if (brk) s = s or 0x10
        if (flagV) s = s or 0x40
        if (flagN) s = s or 0x80
        return s
    }

    private fun unpackStatus(s: Int) {
        flagC = s and 0x01 != 0
        flagZ = s and 0x02 != 0
        flagI = s and 0x04 != 0
        flagD = s and 0x08 != 0
        flagV = s and 0x40 != 0
        flagN = s and 0x80 != 0
    }

    private fun setZN(value: Int) {
        flagZ = (value and 0xFF) == 0
        flagN = value and 0x80 != 0
    }

    // -- Interrupts --
    private fun nmi() {
        push16(pc); push(packStatus()); flagI = true
        pc = read16(0xFFFA); cycles += 7
    }

    private fun irq() {
        push16(pc); push(packStatus()); flagI = true
        pc = read16(0xFFFE); cycles += 7
    }

    // -- Addressing helpers --
    private fun pageCross(a: Int, b: Int): Boolean = (a and 0xFF00) != (b and 0xFF00)

    // -- Execute --
    private fun execute(op: Int) {
        when (op) {
            // BRK
            0x00 -> { pc = (pc + 1) and 0xFFFF; push16(pc); push(packStatus(true)); flagI = true; pc = read16(0xFFFE); cycles += 7 }

            // ORA
            0x01 -> { val addr = izx(); a = a or read(addr); setZN(a); cycles += 6 }
            0x05 -> { val addr = zp(); a = a or read(addr); setZN(a); cycles += 3 }
            0x09 -> { a = a or imm(); setZN(a); cycles += 2 }
            0x0D -> { val addr = abs(); a = a or read(addr); setZN(a); cycles += 4 }
            0x11 -> { val addr = izy(); a = a or read(addr); setZN(a); cycles += 5 + if (pageCrossed) 1 else 0 }
            0x15 -> { val addr = zpx(); a = a or read(addr); setZN(a); cycles += 4 }
            0x19 -> { val addr = aby(); a = a or read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }
            0x1D -> { val addr = abx(); a = a or read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }

            // ASL
            0x06 -> { val addr = zp(); aslMem(addr); cycles += 5 }
            0x0A -> { a = aslAcc(a); cycles += 2 }
            0x0E -> { val addr = abs(); aslMem(addr); cycles += 6 }
            0x16 -> { val addr = zpx(); aslMem(addr); cycles += 6 }
            0x1E -> { val addr = abx(); aslMem(addr); cycles += 7 }

            // PHP/PLP/PHA/PLA
            0x08 -> { push(packStatus(true)); cycles += 3 }
            0x28 -> { unpackStatus(pull()); cycles += 4 }
            0x48 -> { push(a); cycles += 3 }
            0x68 -> { a = pull(); setZN(a); cycles += 4 }

            // BPL/BMI/BVC/BVS/BCC/BCS/BNE/BEQ
            0x10 -> branch(!flagN)
            0x30 -> branch(flagN)
            0x50 -> branch(!flagV)
            0x70 -> branch(flagV)
            0x90 -> branch(!flagC)
            0xB0 -> branch(flagC)
            0xD0 -> branch(!flagZ)
            0xF0 -> branch(flagZ)

            // CLC/SEC/CLI/SEI/CLV/CLD/SED
            0x18 -> { flagC = false; cycles += 2 }
            0x38 -> { flagC = true; cycles += 2 }
            0x58 -> { flagI = false; cycles += 2 }
            0x78 -> { flagI = true; cycles += 2 }
            0xB8 -> { flagV = false; cycles += 2 }
            0xD8 -> { flagD = false; cycles += 2 }
            0xF8 -> { flagD = true; cycles += 2 }

            // JSR
            0x20 -> { val addr = abs(); push16((pc - 1) and 0xFFFF); pc = addr; cycles += 6 }
            // RTS
            0x60 -> { pc = (pull16() + 1) and 0xFFFF; cycles += 6 }
            // RTI
            0x40 -> { unpackStatus(pull()); pc = pull16(); cycles += 6 }

            // AND
            0x21 -> { val addr = izx(); a = a and read(addr); setZN(a); cycles += 6 }
            0x25 -> { val addr = zp(); a = a and read(addr); setZN(a); cycles += 3 }
            0x29 -> { a = a and imm(); setZN(a); cycles += 2 }
            0x2D -> { val addr = abs(); a = a and read(addr); setZN(a); cycles += 4 }
            0x31 -> { val addr = izy(); a = a and read(addr); setZN(a); cycles += 5 + if (pageCrossed) 1 else 0 }
            0x35 -> { val addr = zpx(); a = a and read(addr); setZN(a); cycles += 4 }
            0x39 -> { val addr = aby(); a = a and read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }
            0x3D -> { val addr = abx(); a = a and read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }

            // BIT
            0x24 -> { val addr = zp(); bit(read(addr)); cycles += 3 }
            0x2C -> { val addr = abs(); bit(read(addr)); cycles += 4 }

            // ROL
            0x26 -> { val addr = zp(); rolMem(addr); cycles += 5 }
            0x2A -> { a = rolAcc(a); cycles += 2 }
            0x2E -> { val addr = abs(); rolMem(addr); cycles += 6 }
            0x36 -> { val addr = zpx(); rolMem(addr); cycles += 6 }
            0x3E -> { val addr = abx(); rolMem(addr); cycles += 7 }

            // EOR
            0x41 -> { val addr = izx(); a = a xor read(addr); setZN(a); cycles += 6 }
            0x45 -> { val addr = zp(); a = a xor read(addr); setZN(a); cycles += 3 }
            0x49 -> { a = a xor imm(); setZN(a); cycles += 2 }
            0x4D -> { val addr = abs(); a = a xor read(addr); setZN(a); cycles += 4 }
            0x51 -> { val addr = izy(); a = a xor read(addr); setZN(a); cycles += 5 + if (pageCrossed) 1 else 0 }
            0x55 -> { val addr = zpx(); a = a xor read(addr); setZN(a); cycles += 4 }
            0x59 -> { val addr = aby(); a = a xor read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }
            0x5D -> { val addr = abx(); a = a xor read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }

            // LSR
            0x46 -> { val addr = zp(); lsrMem(addr); cycles += 5 }
            0x4A -> { a = lsrAcc(a); cycles += 2 }
            0x4E -> { val addr = abs(); lsrMem(addr); cycles += 6 }
            0x56 -> { val addr = zpx(); lsrMem(addr); cycles += 6 }
            0x5E -> { val addr = abx(); lsrMem(addr); cycles += 7 }

            // ROR
            0x66 -> { val addr = zp(); rorMem(addr); cycles += 5 }
            0x6A -> { a = rorAcc(a); cycles += 2 }
            0x6E -> { val addr = abs(); rorMem(addr); cycles += 6 }
            0x76 -> { val addr = zpx(); rorMem(addr); cycles += 6 }
            0x7E -> { val addr = abx(); rorMem(addr); cycles += 7 }

            // JMP
            0x4C -> { pc = abs(); cycles += 3 }
            0x6C -> { val addr = abs(); pc = read16Bug(addr); cycles += 5 }

            // ADC
            0x61 -> { val addr = izx(); adc(read(addr)); cycles += 6 }
            0x65 -> { val addr = zp(); adc(read(addr)); cycles += 3 }
            0x69 -> { adc(imm()); cycles += 2 }
            0x6D -> { val addr = abs(); adc(read(addr)); cycles += 4 }
            0x71 -> { val addr = izy(); adc(read(addr)); cycles += 5 + if (pageCrossed) 1 else 0 }
            0x75 -> { val addr = zpx(); adc(read(addr)); cycles += 4 }
            0x79 -> { val addr = aby(); adc(read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }
            0x7D -> { val addr = abx(); adc(read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }

            // SBC
            0xE1 -> { val addr = izx(); sbc(read(addr)); cycles += 6 }
            0xE5 -> { val addr = zp(); sbc(read(addr)); cycles += 3 }
            0xE9 -> { sbc(imm()); cycles += 2 }
            0xEB -> { sbc(imm()); cycles += 2 } // unofficial
            0xED -> { val addr = abs(); sbc(read(addr)); cycles += 4 }
            0xF1 -> { val addr = izy(); sbc(read(addr)); cycles += 5 + if (pageCrossed) 1 else 0 }
            0xF5 -> { val addr = zpx(); sbc(read(addr)); cycles += 4 }
            0xF9 -> { val addr = aby(); sbc(read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }
            0xFD -> { val addr = abx(); sbc(read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }

            // STA
            0x81 -> { val addr = izx(); write(addr, a); cycles += 6 }
            0x85 -> { val addr = zp(); write(addr, a); cycles += 3 }
            0x8D -> { val addr = abs(); write(addr, a); cycles += 4 }
            0x91 -> { val addr = izy(); write(addr, a); cycles += 6 }
            0x95 -> { val addr = zpx(); write(addr, a); cycles += 4 }
            0x99 -> { val addr = aby(); write(addr, a); cycles += 5 }
            0x9D -> { val addr = abx(); write(addr, a); cycles += 5 }

            // STX
            0x86 -> { val addr = zp(); write(addr, x); cycles += 3 }
            0x8E -> { val addr = abs(); write(addr, x); cycles += 4 }
            0x96 -> { val addr = zpy(); write(addr, x); cycles += 4 }

            // STY
            0x84 -> { val addr = zp(); write(addr, y); cycles += 3 }
            0x8C -> { val addr = abs(); write(addr, y); cycles += 4 }
            0x94 -> { val addr = zpx(); write(addr, y); cycles += 4 }

            // LDA
            0xA1 -> { val addr = izx(); a = read(addr); setZN(a); cycles += 6 }
            0xA5 -> { val addr = zp(); a = read(addr); setZN(a); cycles += 3 }
            0xA9 -> { a = imm(); setZN(a); cycles += 2 }
            0xAD -> { val addr = abs(); a = read(addr); setZN(a); cycles += 4 }
            0xB1 -> { val addr = izy(); a = read(addr); setZN(a); cycles += 5 + if (pageCrossed) 1 else 0 }
            0xB5 -> { val addr = zpx(); a = read(addr); setZN(a); cycles += 4 }
            0xB9 -> { val addr = aby(); a = read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }
            0xBD -> { val addr = abx(); a = read(addr); setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }

            // LDX
            0xA2 -> { x = imm(); setZN(x); cycles += 2 }
            0xA6 -> { val addr = zp(); x = read(addr); setZN(x); cycles += 3 }
            0xAE -> { val addr = abs(); x = read(addr); setZN(x); cycles += 4 }
            0xB6 -> { val addr = zpy(); x = read(addr); setZN(x); cycles += 4 }
            0xBE -> { val addr = aby(); x = read(addr); setZN(x); cycles += 4 + if (pageCrossed) 1 else 0 }

            // LDY
            0xA0 -> { y = imm(); setZN(y); cycles += 2 }
            0xA4 -> { val addr = zp(); y = read(addr); setZN(y); cycles += 3 }
            0xAC -> { val addr = abs(); y = read(addr); setZN(y); cycles += 4 }
            0xB4 -> { val addr = zpx(); y = read(addr); setZN(y); cycles += 4 }
            0xBC -> { val addr = abx(); y = read(addr); setZN(y); cycles += 4 + if (pageCrossed) 1 else 0 }

            // CMP
            0xC1 -> { val addr = izx(); cmp(a, read(addr)); cycles += 6 }
            0xC5 -> { val addr = zp(); cmp(a, read(addr)); cycles += 3 }
            0xC9 -> { cmp(a, imm()); cycles += 2 }
            0xCD -> { val addr = abs(); cmp(a, read(addr)); cycles += 4 }
            0xD1 -> { val addr = izy(); cmp(a, read(addr)); cycles += 5 + if (pageCrossed) 1 else 0 }
            0xD5 -> { val addr = zpx(); cmp(a, read(addr)); cycles += 4 }
            0xD9 -> { val addr = aby(); cmp(a, read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }
            0xDD -> { val addr = abx(); cmp(a, read(addr)); cycles += 4 + if (pageCrossed) 1 else 0 }

            // CPX
            0xE0 -> { cmp(x, imm()); cycles += 2 }
            0xE4 -> { val addr = zp(); cmp(x, read(addr)); cycles += 3 }
            0xEC -> { val addr = abs(); cmp(x, read(addr)); cycles += 4 }

            // CPY
            0xC0 -> { cmp(y, imm()); cycles += 2 }
            0xC4 -> { val addr = zp(); cmp(y, read(addr)); cycles += 3 }
            0xCC -> { val addr = abs(); cmp(y, read(addr)); cycles += 4 }

            // DEC
            0xC6 -> { val addr = zp(); decMem(addr); cycles += 5 }
            0xCE -> { val addr = abs(); decMem(addr); cycles += 6 }
            0xD6 -> { val addr = zpx(); decMem(addr); cycles += 6 }
            0xDE -> { val addr = abx(); decMem(addr); cycles += 7 }

            // INC
            0xE6 -> { val addr = zp(); incMem(addr); cycles += 5 }
            0xEE -> { val addr = abs(); incMem(addr); cycles += 6 }
            0xF6 -> { val addr = zpx(); incMem(addr); cycles += 6 }
            0xFE -> { val addr = abx(); incMem(addr); cycles += 7 }

            // DEX/DEY/INX/INY
            0xCA -> { x = (x - 1) and 0xFF; setZN(x); cycles += 2 }
            0x88 -> { y = (y - 1) and 0xFF; setZN(y); cycles += 2 }
            0xE8 -> { x = (x + 1) and 0xFF; setZN(x); cycles += 2 }
            0xC8 -> { y = (y + 1) and 0xFF; setZN(y); cycles += 2 }

            // TAX/TXA/TAY/TYA/TSX/TXS
            0xAA -> { x = a; setZN(x); cycles += 2 }
            0x8A -> { a = x; setZN(a); cycles += 2 }
            0xA8 -> { y = a; setZN(y); cycles += 2 }
            0x98 -> { a = y; setZN(a); cycles += 2 }
            0xBA -> { x = sp; setZN(x); cycles += 2 }
            0x9A -> { sp = x; cycles += 2 }

            // NOP
            0xEA -> { cycles += 2 }
            // Unofficial NOPs
            0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA -> { cycles += 2 }
            0x04, 0x44, 0x64 -> { pc = (pc + 1) and 0xFFFF; cycles += 3 }
            0x0C -> { pc = (pc + 2) and 0xFFFF; cycles += 4 }
            0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> { pc = (pc + 1) and 0xFFFF; cycles += 4 }
            0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC -> { abx(); cycles += 4 + if (pageCrossed) 1 else 0 }
            0x80, 0x82, 0x89, 0xC2, 0xE2 -> { pc = (pc + 1) and 0xFFFF; cycles += 2 }

            // LAX (unofficial but used by some games)
            0xA3 -> { val addr = izx(); a = read(addr); x = a; setZN(a); cycles += 6 }
            0xA7 -> { val addr = zp(); a = read(addr); x = a; setZN(a); cycles += 3 }
            0xAF -> { val addr = abs(); a = read(addr); x = a; setZN(a); cycles += 4 }
            0xB3 -> { val addr = izy(); a = read(addr); x = a; setZN(a); cycles += 5 + if (pageCrossed) 1 else 0 }
            0xB7 -> { val addr = zpy(); a = read(addr); x = a; setZN(a); cycles += 4 }
            0xBF -> { val addr = aby(); a = read(addr); x = a; setZN(a); cycles += 4 + if (pageCrossed) 1 else 0 }

            // SAX (unofficial)
            0x83 -> { val addr = izx(); write(addr, a and x); cycles += 6 }
            0x87 -> { val addr = zp(); write(addr, a and x); cycles += 3 }
            0x8F -> { val addr = abs(); write(addr, a and x); cycles += 4 }
            0x97 -> { val addr = zpy(); write(addr, a and x); cycles += 4 }

            // DCP (unofficial)
            0xC3 -> { val addr = izx(); dcp(addr); cycles += 8 }
            0xC7 -> { val addr = zp(); dcp(addr); cycles += 5 }
            0xCF -> { val addr = abs(); dcp(addr); cycles += 6 }
            0xD3 -> { val addr = izy(); dcp(addr); cycles += 8 }
            0xD7 -> { val addr = zpx(); dcp(addr); cycles += 6 }
            0xDB -> { val addr = aby(); dcp(addr); cycles += 7 }
            0xDF -> { val addr = abx(); dcp(addr); cycles += 7 }

            // ISB/ISC (unofficial)
            0xE3 -> { val addr = izx(); isb(addr); cycles += 8 }
            0xE7 -> { val addr = zp(); isb(addr); cycles += 5 }
            0xEF -> { val addr = abs(); isb(addr); cycles += 6 }
            0xF3 -> { val addr = izy(); isb(addr); cycles += 8 }
            0xF7 -> { val addr = zpx(); isb(addr); cycles += 6 }
            0xFB -> { val addr = aby(); isb(addr); cycles += 7 }
            0xFF -> { val addr = abx(); isb(addr); cycles += 7 }

            // SLO (unofficial)
            0x03 -> { val addr = izx(); slo(addr); cycles += 8 }
            0x07 -> { val addr = zp(); slo(addr); cycles += 5 }
            0x0F -> { val addr = abs(); slo(addr); cycles += 6 }
            0x13 -> { val addr = izy(); slo(addr); cycles += 8 }
            0x17 -> { val addr = zpx(); slo(addr); cycles += 6 }
            0x1B -> { val addr = aby(); slo(addr); cycles += 7 }
            0x1F -> { val addr = abx(); slo(addr); cycles += 7 }

            // RLA (unofficial)
            0x23 -> { val addr = izx(); rla(addr); cycles += 8 }
            0x27 -> { val addr = zp(); rla(addr); cycles += 5 }
            0x2F -> { val addr = abs(); rla(addr); cycles += 6 }
            0x33 -> { val addr = izy(); rla(addr); cycles += 8 }
            0x37 -> { val addr = zpx(); rla(addr); cycles += 6 }
            0x3B -> { val addr = aby(); rla(addr); cycles += 7 }
            0x3F -> { val addr = abx(); rla(addr); cycles += 7 }

            // SRE (unofficial)
            0x43 -> { val addr = izx(); sre(addr); cycles += 8 }
            0x47 -> { val addr = zp(); sre(addr); cycles += 5 }
            0x4F -> { val addr = abs(); sre(addr); cycles += 6 }
            0x53 -> { val addr = izy(); sre(addr); cycles += 8 }
            0x57 -> { val addr = zpx(); sre(addr); cycles += 6 }
            0x5B -> { val addr = aby(); sre(addr); cycles += 7 }
            0x5F -> { val addr = abx(); sre(addr); cycles += 7 }

            // RRA (unofficial)
            0x63 -> { val addr = izx(); rra(addr); cycles += 8 }
            0x67 -> { val addr = zp(); rra(addr); cycles += 5 }
            0x6F -> { val addr = abs(); rra(addr); cycles += 6 }
            0x73 -> { val addr = izy(); rra(addr); cycles += 8 }
            0x77 -> { val addr = zpx(); rra(addr); cycles += 6 }
            0x7B -> { val addr = aby(); rra(addr); cycles += 7 }
            0x7F -> { val addr = abx(); rra(addr); cycles += 7 }

            // Catch-all for unimplemented opcodes
            else -> { cycles += 2 }
        }
    }

    // -- Addressing modes --
    private fun imm(): Int { val v = read(pc); pc = (pc + 1) and 0xFFFF; return v }
    private fun zp(): Int { val v = read(pc); pc = (pc + 1) and 0xFFFF; return v }
    private fun zpx(): Int { val v = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF; return v }
    private fun zpy(): Int { val v = (read(pc) + y) and 0xFF; pc = (pc + 1) and 0xFFFF; return v }
    private fun abs(): Int { val v = read16(pc); pc = (pc + 2) and 0xFFFF; return v }
    private fun abx(): Int {
        val base = read16(pc); pc = (pc + 2) and 0xFFFF
        val addr = (base + x) and 0xFFFF
        pageCrossed = pageCross(base, addr)
        return addr
    }
    private fun aby(): Int {
        val base = read16(pc); pc = (pc + 2) and 0xFFFF
        val addr = (base + y) and 0xFFFF
        pageCrossed = pageCross(base, addr)
        return addr
    }
    private fun izx(): Int {
        val ptr = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF
        return read(ptr) or (read((ptr + 1) and 0xFF) shl 8)
    }
    private fun izy(): Int {
        val ptr = read(pc); pc = (pc + 1) and 0xFFFF
        val base = read(ptr) or (read((ptr + 1) and 0xFF) shl 8)
        val addr = (base + y) and 0xFFFF
        pageCrossed = pageCross(base, addr)
        return addr
    }

    // -- Branch --
    private fun branch(condition: Boolean) {
        val offset = read(pc); pc = (pc + 1) and 0xFFFF
        cycles += 2
        if (condition) {
            val rel = if (offset >= 0x80) offset - 256 else offset
            val newPc = (pc + rel) and 0xFFFF
            cycles += if (pageCross(pc, newPc)) 2 else 1
            pc = newPc
        }
    }

    // -- ALU operations --
    private fun adc(value: Int) {
        val carry = if (flagC) 1 else 0
        val sum = a + value + carry
        flagC = sum > 0xFF
        flagV = ((a xor value).inv() and (a xor sum) and 0x80) != 0
        a = sum and 0xFF
        setZN(a)
    }

    private fun sbc(value: Int) { adc(value xor 0xFF) }

    private fun cmp(reg: Int, value: Int) {
        val diff = reg - value
        flagC = diff >= 0
        setZN(diff and 0xFF)
    }

    private fun bit(value: Int) {
        flagZ = (a and value) == 0
        flagV = value and 0x40 != 0
        flagN = value and 0x80 != 0
    }

    // -- Shifts --
    private fun aslAcc(v: Int): Int {
        flagC = v and 0x80 != 0
        val r = (v shl 1) and 0xFF; setZN(r); return r
    }
    private fun aslMem(addr: Int) { val v = read(addr); write(addr, aslAcc(v)) }

    private fun lsrAcc(v: Int): Int {
        flagC = v and 1 != 0
        val r = (v shr 1) and 0xFF; setZN(r); return r
    }
    private fun lsrMem(addr: Int) { val v = read(addr); write(addr, lsrAcc(v)) }

    private fun rolAcc(v: Int): Int {
        val c = if (flagC) 1 else 0
        flagC = v and 0x80 != 0
        val r = ((v shl 1) or c) and 0xFF; setZN(r); return r
    }
    private fun rolMem(addr: Int) { val v = read(addr); write(addr, rolAcc(v)) }

    private fun rorAcc(v: Int): Int {
        val c = if (flagC) 0x80 else 0
        flagC = v and 1 != 0
        val r = ((v shr 1) or c) and 0xFF; setZN(r); return r
    }
    private fun rorMem(addr: Int) { val v = read(addr); write(addr, rorAcc(v)) }

    private fun decMem(addr: Int) { val v = (read(addr) - 1) and 0xFF; write(addr, v); setZN(v) }
    private fun incMem(addr: Int) { val v = (read(addr) + 1) and 0xFF; write(addr, v); setZN(v) }

    // -- Unofficial combo ops --
    private fun dcp(addr: Int) { val v = (read(addr) - 1) and 0xFF; write(addr, v); cmp(a, v) }
    private fun isb(addr: Int) { val v = (read(addr) + 1) and 0xFF; write(addr, v); sbc(v) }
    private fun slo(addr: Int) { val m = read(addr); flagC = m and 0x80 != 0; val r = (m shl 1) and 0xFF; write(addr, r); a = a or r; setZN(a) }
    private fun rla(addr: Int) { val m = read(addr); val c = if (flagC) 1 else 0; flagC = m and 0x80 != 0; val r = ((m shl 1) or c) and 0xFF; write(addr, r); a = a and r; setZN(a) }
    private fun sre(addr: Int) { val m = read(addr); flagC = m and 1 != 0; val r = (m shr 1) and 0xFF; write(addr, r); a = a xor r; setZN(a) }
    private fun rra(addr: Int) { val m = read(addr); val c = if (flagC) 0x80 else 0; flagC = m and 1 != 0; val r = ((m shr 1) or c) and 0xFF; write(addr, r); adc(r) }
}
