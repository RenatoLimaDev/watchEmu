#pragma once
#include "cpu.h"
#include "nes.h"

namespace nesemu {

inline int Cpu::read(int addr) { return nes->cpuRead(addr & 0xFFFF); }
inline void Cpu::write(int addr, int value) { nes->cpuWrite(addr & 0xFFFF, value); }
inline int Cpu::read16(int addr) { return read(addr) | (read(addr + 1) << 8); }
inline int Cpu::read16Bug(int addr) {
    int lo = read(addr);
    int hi = read((addr & 0xFF00) | ((addr + 1) & 0xFF));
    return lo | (hi << 8);
}

inline void Cpu::push(int value) { write(0x100 + sp, value); sp = (sp - 1) & 0xFF; }
inline void Cpu::push16(int value) { push((value >> 8) & 0xFF); push(value & 0xFF); }
inline int Cpu::pull() { sp = (sp + 1) & 0xFF; return read(0x100 + sp); }
inline int Cpu::pull16() { int lo = pull(); return lo | (pull() << 8); }

inline int Cpu::packStatus(bool brk) {
    int s = 0x20;
    if (flagC) s |= 0x01;
    if (flagZ) s |= 0x02;
    if (flagI) s |= 0x04;
    if (flagD) s |= 0x08;
    if (brk) s |= 0x10;
    if (flagV) s |= 0x40;
    if (flagN) s |= 0x80;
    return s;
}
inline void Cpu::unpackStatus(int s) {
    flagC = (s & 0x01) != 0;
    flagZ = (s & 0x02) != 0;
    flagI = (s & 0x04) != 0;
    flagD = (s & 0x08) != 0;
    flagV = (s & 0x40) != 0;
    flagN = (s & 0x80) != 0;
}
inline void Cpu::setZN(int value) {
    flagZ = (value & 0xFF) == 0;
    flagN = (value & 0x80) != 0;
}

inline void Cpu::nmi() {
    push16(pc); push(packStatus()); flagI = true;
    pc = read16(0xFFFA); cycles += 7;
}
inline void Cpu::irq() {
    push16(pc); push(packStatus()); flagI = true;
    pc = read16(0xFFFE); cycles += 7;
}

inline void Cpu::reset() {
    a = 0; x = 0; y = 0; sp = 0xFD;
    pc = read16(0xFFFC);
    flagI = true; flagC = false; flagZ = false; flagD = false;
    flagB = false; flagV = false; flagN = false;
    stallCycles = 0;
}

inline int Cpu::step() {
    if (stallCycles > 0) { stallCycles--; return 1; }
    int startCycles = cycles;
    if (nes->nmiPending) { nes->nmiPending = false; nmi(); return cycles - startCycles; }
    if (nes->irqPending() && !flagI) { irq(); return cycles - startCycles; }
    int opcode = read(pc); pc = (pc + 1) & 0xFFFF;
    execute(opcode);
    return cycles - startCycles;
}

// -- Addressing --
inline int Cpu::imm() { int v = read(pc); pc = (pc + 1) & 0xFFFF; return v; }
inline int Cpu::zp() { int v = read(pc); pc = (pc + 1) & 0xFFFF; return v; }
inline int Cpu::zpx() { int v = (read(pc) + x) & 0xFF; pc = (pc + 1) & 0xFFFF; return v; }
inline int Cpu::zpy() { int v = (read(pc) + y) & 0xFF; pc = (pc + 1) & 0xFFFF; return v; }
inline int Cpu::absAddr() { int v = read16(pc); pc = (pc + 2) & 0xFFFF; return v; }
inline int Cpu::abx() {
    int base = read16(pc); pc = (pc + 2) & 0xFFFF;
    int addr = (base + x) & 0xFFFF;
    pageCrossed = pageCross(base, addr);
    return addr;
}
inline int Cpu::aby() {
    int base = read16(pc); pc = (pc + 2) & 0xFFFF;
    int addr = (base + y) & 0xFFFF;
    pageCrossed = pageCross(base, addr);
    return addr;
}
inline int Cpu::izx() {
    int ptr = (read(pc) + x) & 0xFF; pc = (pc + 1) & 0xFFFF;
    return read(ptr) | (read((ptr + 1) & 0xFF) << 8);
}
inline int Cpu::izy() {
    int ptr = read(pc); pc = (pc + 1) & 0xFFFF;
    int base = read(ptr) | (read((ptr + 1) & 0xFF) << 8);
    int addr = (base + y) & 0xFFFF;
    pageCrossed = pageCross(base, addr);
    return addr;
}

inline void Cpu::branch(bool condition) {
    int offset = read(pc); pc = (pc + 1) & 0xFFFF;
    cycles += 2;
    if (condition) {
        int rel = (offset >= 0x80) ? offset - 256 : offset;
        int newPc = (pc + rel) & 0xFFFF;
        cycles += pageCross(pc, newPc) ? 2 : 1;
        pc = newPc;
    }
}

// -- ALU --
inline void Cpu::adc(int value) {
    int carry = flagC ? 1 : 0;
    int sum = a + value + carry;
    flagC = sum > 0xFF;
    flagV = ((~(a ^ value)) & (a ^ sum) & 0x80) != 0;
    a = sum & 0xFF;
    setZN(a);
}
inline void Cpu::sbc(int value) { adc(value ^ 0xFF); }
inline void Cpu::cmp(int reg, int value) {
    int diff = reg - value;
    flagC = diff >= 0;
    setZN(diff & 0xFF);
}
inline void Cpu::bitOp(int value) {
    flagZ = (a & value) == 0;
    flagV = (value & 0x40) != 0;
    flagN = (value & 0x80) != 0;
}

inline int Cpu::aslAcc(int v) { flagC = (v & 0x80) != 0; int r = (v << 1) & 0xFF; setZN(r); return r; }
inline void Cpu::aslMem(int addr) { int v = read(addr); write(addr, aslAcc(v)); }
inline int Cpu::lsrAcc(int v) { flagC = (v & 1) != 0; int r = (v >> 1) & 0xFF; setZN(r); return r; }
inline void Cpu::lsrMem(int addr) { int v = read(addr); write(addr, lsrAcc(v)); }
inline int Cpu::rolAcc(int v) { int c = flagC ? 1 : 0; flagC = (v & 0x80) != 0; int r = ((v << 1) | c) & 0xFF; setZN(r); return r; }
inline void Cpu::rolMem(int addr) { int v = read(addr); write(addr, rolAcc(v)); }
inline int Cpu::rorAcc(int v) { int c = flagC ? 0x80 : 0; flagC = (v & 1) != 0; int r = ((v >> 1) | c) & 0xFF; setZN(r); return r; }
inline void Cpu::rorMem(int addr) { int v = read(addr); write(addr, rorAcc(v)); }
inline void Cpu::decMem(int addr) { int v = (read(addr) - 1) & 0xFF; write(addr, v); setZN(v); }
inline void Cpu::incMem(int addr) { int v = (read(addr) + 1) & 0xFF; write(addr, v); setZN(v); }

inline void Cpu::dcp(int addr) { int v = (read(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); }
inline void Cpu::isb(int addr) { int v = (read(addr) + 1) & 0xFF; write(addr, v); sbc(v); }
inline void Cpu::slo(int addr) { int m = read(addr); flagC = (m & 0x80) != 0; int r = (m << 1) & 0xFF; write(addr, r); a = a | r; setZN(a); }
inline void Cpu::rla(int addr) { int m = read(addr); int c = flagC ? 1 : 0; flagC = (m & 0x80) != 0; int r = ((m << 1) | c) & 0xFF; write(addr, r); a = a & r; setZN(a); }
inline void Cpu::sre(int addr) { int m = read(addr); flagC = (m & 1) != 0; int r = (m >> 1) & 0xFF; write(addr, r); a = a ^ r; setZN(a); }
inline void Cpu::rra(int addr) { int m = read(addr); int c = flagC ? 0x80 : 0; flagC = (m & 1) != 0; int r = ((m >> 1) | c) & 0xFF; write(addr, r); adc(r); }

inline void Cpu::execute(int op) {
    switch (op) {
        // BRK
        case 0x00: pc = (pc + 1) & 0xFFFF; push16(pc); push(packStatus(true)); flagI = true; pc = read16(0xFFFE); cycles += 7; break;

        // ORA
        case 0x01: { int addr = izx(); a = a | read(addr); setZN(a); cycles += 6; } break;
        case 0x05: { int addr = zp(); a = a | read(addr); setZN(a); cycles += 3; } break;
        case 0x09: a = a | imm(); setZN(a); cycles += 2; break;
        case 0x0D: { int addr = absAddr(); a = a | read(addr); setZN(a); cycles += 4; } break;
        case 0x11: { int addr = izy(); a = a | read(addr); setZN(a); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0x15: { int addr = zpx(); a = a | read(addr); setZN(a); cycles += 4; } break;
        case 0x19: { int addr = aby(); a = a | read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0x1D: { int addr = abx(); a = a | read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // ASL
        case 0x06: { int addr = zp(); aslMem(addr); cycles += 5; } break;
        case 0x0A: a = aslAcc(a); cycles += 2; break;
        case 0x0E: { int addr = absAddr(); aslMem(addr); cycles += 6; } break;
        case 0x16: { int addr = zpx(); aslMem(addr); cycles += 6; } break;
        case 0x1E: { int addr = abx(); aslMem(addr); cycles += 7; } break;

        // PHP/PLP/PHA/PLA
        case 0x08: push(packStatus(true)); cycles += 3; break;
        case 0x28: unpackStatus(pull()); cycles += 4; break;
        case 0x48: push(a); cycles += 3; break;
        case 0x68: a = pull(); setZN(a); cycles += 4; break;

        // Branches
        case 0x10: branch(!flagN); break;
        case 0x30: branch(flagN); break;
        case 0x50: branch(!flagV); break;
        case 0x70: branch(flagV); break;
        case 0x90: branch(!flagC); break;
        case 0xB0: branch(flagC); break;
        case 0xD0: branch(!flagZ); break;
        case 0xF0: branch(flagZ); break;

        // Flag ops
        case 0x18: flagC = false; cycles += 2; break;
        case 0x38: flagC = true; cycles += 2; break;
        case 0x58: flagI = false; cycles += 2; break;
        case 0x78: flagI = true; cycles += 2; break;
        case 0xB8: flagV = false; cycles += 2; break;
        case 0xD8: flagD = false; cycles += 2; break;
        case 0xF8: flagD = true; cycles += 2; break;

        // JSR/RTS/RTI
        case 0x20: { int addr = absAddr(); push16((pc - 1) & 0xFFFF); pc = addr; cycles += 6; } break;
        case 0x60: pc = (pull16() + 1) & 0xFFFF; cycles += 6; break;
        case 0x40: unpackStatus(pull()); pc = pull16(); cycles += 6; break;

        // AND
        case 0x21: { int addr = izx(); a = a & read(addr); setZN(a); cycles += 6; } break;
        case 0x25: { int addr = zp(); a = a & read(addr); setZN(a); cycles += 3; } break;
        case 0x29: a = a & imm(); setZN(a); cycles += 2; break;
        case 0x2D: { int addr = absAddr(); a = a & read(addr); setZN(a); cycles += 4; } break;
        case 0x31: { int addr = izy(); a = a & read(addr); setZN(a); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0x35: { int addr = zpx(); a = a & read(addr); setZN(a); cycles += 4; } break;
        case 0x39: { int addr = aby(); a = a & read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0x3D: { int addr = abx(); a = a & read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // BIT
        case 0x24: { int addr = zp(); bitOp(read(addr)); cycles += 3; } break;
        case 0x2C: { int addr = absAddr(); bitOp(read(addr)); cycles += 4; } break;

        // ROL
        case 0x26: { int addr = zp(); rolMem(addr); cycles += 5; } break;
        case 0x2A: a = rolAcc(a); cycles += 2; break;
        case 0x2E: { int addr = absAddr(); rolMem(addr); cycles += 6; } break;
        case 0x36: { int addr = zpx(); rolMem(addr); cycles += 6; } break;
        case 0x3E: { int addr = abx(); rolMem(addr); cycles += 7; } break;

        // EOR
        case 0x41: { int addr = izx(); a = a ^ read(addr); setZN(a); cycles += 6; } break;
        case 0x45: { int addr = zp(); a = a ^ read(addr); setZN(a); cycles += 3; } break;
        case 0x49: a = a ^ imm(); setZN(a); cycles += 2; break;
        case 0x4D: { int addr = absAddr(); a = a ^ read(addr); setZN(a); cycles += 4; } break;
        case 0x51: { int addr = izy(); a = a ^ read(addr); setZN(a); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0x55: { int addr = zpx(); a = a ^ read(addr); setZN(a); cycles += 4; } break;
        case 0x59: { int addr = aby(); a = a ^ read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0x5D: { int addr = abx(); a = a ^ read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // LSR
        case 0x46: { int addr = zp(); lsrMem(addr); cycles += 5; } break;
        case 0x4A: a = lsrAcc(a); cycles += 2; break;
        case 0x4E: { int addr = absAddr(); lsrMem(addr); cycles += 6; } break;
        case 0x56: { int addr = zpx(); lsrMem(addr); cycles += 6; } break;
        case 0x5E: { int addr = abx(); lsrMem(addr); cycles += 7; } break;

        // ROR
        case 0x66: { int addr = zp(); rorMem(addr); cycles += 5; } break;
        case 0x6A: a = rorAcc(a); cycles += 2; break;
        case 0x6E: { int addr = absAddr(); rorMem(addr); cycles += 6; } break;
        case 0x76: { int addr = zpx(); rorMem(addr); cycles += 6; } break;
        case 0x7E: { int addr = abx(); rorMem(addr); cycles += 7; } break;

        // JMP
        case 0x4C: pc = absAddr(); cycles += 3; break;
        case 0x6C: { int addr = absAddr(); pc = read16Bug(addr); cycles += 5; } break;

        // ADC
        case 0x61: { int addr = izx(); adc(read(addr)); cycles += 6; } break;
        case 0x65: { int addr = zp(); adc(read(addr)); cycles += 3; } break;
        case 0x69: adc(imm()); cycles += 2; break;
        case 0x6D: { int addr = absAddr(); adc(read(addr)); cycles += 4; } break;
        case 0x71: { int addr = izy(); adc(read(addr)); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0x75: { int addr = zpx(); adc(read(addr)); cycles += 4; } break;
        case 0x79: { int addr = aby(); adc(read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0x7D: { int addr = abx(); adc(read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // SBC
        case 0xE1: { int addr = izx(); sbc(read(addr)); cycles += 6; } break;
        case 0xE5: { int addr = zp(); sbc(read(addr)); cycles += 3; } break;
        case 0xE9: sbc(imm()); cycles += 2; break;
        case 0xEB: sbc(imm()); cycles += 2; break; // unofficial
        case 0xED: { int addr = absAddr(); sbc(read(addr)); cycles += 4; } break;
        case 0xF1: { int addr = izy(); sbc(read(addr)); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0xF5: { int addr = zpx(); sbc(read(addr)); cycles += 4; } break;
        case 0xF9: { int addr = aby(); sbc(read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0xFD: { int addr = abx(); sbc(read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // STA
        case 0x81: { int addr = izx(); write(addr, a); cycles += 6; } break;
        case 0x85: { int addr = zp(); write(addr, a); cycles += 3; } break;
        case 0x8D: { int addr = absAddr(); write(addr, a); cycles += 4; } break;
        case 0x91: { int addr = izy(); write(addr, a); cycles += 6; } break;
        case 0x95: { int addr = zpx(); write(addr, a); cycles += 4; } break;
        case 0x99: { int addr = aby(); write(addr, a); cycles += 5; } break;
        case 0x9D: { int addr = abx(); write(addr, a); cycles += 5; } break;

        // STX
        case 0x86: { int addr = zp(); write(addr, x); cycles += 3; } break;
        case 0x8E: { int addr = absAddr(); write(addr, x); cycles += 4; } break;
        case 0x96: { int addr = zpy(); write(addr, x); cycles += 4; } break;

        // STY
        case 0x84: { int addr = zp(); write(addr, y); cycles += 3; } break;
        case 0x8C: { int addr = absAddr(); write(addr, y); cycles += 4; } break;
        case 0x94: { int addr = zpx(); write(addr, y); cycles += 4; } break;

        // LDA
        case 0xA1: { int addr = izx(); a = read(addr); setZN(a); cycles += 6; } break;
        case 0xA5: { int addr = zp(); a = read(addr); setZN(a); cycles += 3; } break;
        case 0xA9: a = imm(); setZN(a); cycles += 2; break;
        case 0xAD: { int addr = absAddr(); a = read(addr); setZN(a); cycles += 4; } break;
        case 0xB1: { int addr = izy(); a = read(addr); setZN(a); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0xB5: { int addr = zpx(); a = read(addr); setZN(a); cycles += 4; } break;
        case 0xB9: { int addr = aby(); a = read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0xBD: { int addr = abx(); a = read(addr); setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // LDX
        case 0xA2: x = imm(); setZN(x); cycles += 2; break;
        case 0xA6: { int addr = zp(); x = read(addr); setZN(x); cycles += 3; } break;
        case 0xAE: { int addr = absAddr(); x = read(addr); setZN(x); cycles += 4; } break;
        case 0xB6: { int addr = zpy(); x = read(addr); setZN(x); cycles += 4; } break;
        case 0xBE: { int addr = aby(); x = read(addr); setZN(x); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // LDY
        case 0xA0: y = imm(); setZN(y); cycles += 2; break;
        case 0xA4: { int addr = zp(); y = read(addr); setZN(y); cycles += 3; } break;
        case 0xAC: { int addr = absAddr(); y = read(addr); setZN(y); cycles += 4; } break;
        case 0xB4: { int addr = zpx(); y = read(addr); setZN(y); cycles += 4; } break;
        case 0xBC: { int addr = abx(); y = read(addr); setZN(y); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // CMP
        case 0xC1: { int addr = izx(); cmp(a, read(addr)); cycles += 6; } break;
        case 0xC5: { int addr = zp(); cmp(a, read(addr)); cycles += 3; } break;
        case 0xC9: cmp(a, imm()); cycles += 2; break;
        case 0xCD: { int addr = absAddr(); cmp(a, read(addr)); cycles += 4; } break;
        case 0xD1: { int addr = izy(); cmp(a, read(addr)); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0xD5: { int addr = zpx(); cmp(a, read(addr)); cycles += 4; } break;
        case 0xD9: { int addr = aby(); cmp(a, read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0xDD: { int addr = abx(); cmp(a, read(addr)); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // CPX
        case 0xE0: cmp(x, imm()); cycles += 2; break;
        case 0xE4: { int addr = zp(); cmp(x, read(addr)); cycles += 3; } break;
        case 0xEC: { int addr = absAddr(); cmp(x, read(addr)); cycles += 4; } break;

        // CPY
        case 0xC0: cmp(y, imm()); cycles += 2; break;
        case 0xC4: { int addr = zp(); cmp(y, read(addr)); cycles += 3; } break;
        case 0xCC: { int addr = absAddr(); cmp(y, read(addr)); cycles += 4; } break;

        // DEC
        case 0xC6: { int addr = zp(); decMem(addr); cycles += 5; } break;
        case 0xCE: { int addr = absAddr(); decMem(addr); cycles += 6; } break;
        case 0xD6: { int addr = zpx(); decMem(addr); cycles += 6; } break;
        case 0xDE: { int addr = abx(); decMem(addr); cycles += 7; } break;

        // INC
        case 0xE6: { int addr = zp(); incMem(addr); cycles += 5; } break;
        case 0xEE: { int addr = absAddr(); incMem(addr); cycles += 6; } break;
        case 0xF6: { int addr = zpx(); incMem(addr); cycles += 6; } break;
        case 0xFE: { int addr = abx(); incMem(addr); cycles += 7; } break;

        // DEX/DEY/INX/INY
        case 0xCA: x = (x - 1) & 0xFF; setZN(x); cycles += 2; break;
        case 0x88: y = (y - 1) & 0xFF; setZN(y); cycles += 2; break;
        case 0xE8: x = (x + 1) & 0xFF; setZN(x); cycles += 2; break;
        case 0xC8: y = (y + 1) & 0xFF; setZN(y); cycles += 2; break;

        // Transfers
        case 0xAA: x = a; setZN(x); cycles += 2; break;
        case 0x8A: a = x; setZN(a); cycles += 2; break;
        case 0xA8: y = a; setZN(y); cycles += 2; break;
        case 0x98: a = y; setZN(a); cycles += 2; break;
        case 0xBA: x = sp; setZN(x); cycles += 2; break;
        case 0x9A: sp = x; cycles += 2; break;

        // NOP + unofficial NOPs
        case 0xEA: cycles += 2; break;
        case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA: cycles += 2; break;
        case 0x04: case 0x44: case 0x64: pc = (pc + 1) & 0xFFFF; cycles += 3; break;
        case 0x0C: pc = (pc + 2) & 0xFFFF; cycles += 4; break;
        case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4: pc = (pc + 1) & 0xFFFF; cycles += 4; break;
        case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC: { abx(); cycles += 4 + (pageCrossed ? 1 : 0); } break;
        case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2: pc = (pc + 1) & 0xFFFF; cycles += 2; break;

        // LAX (unofficial)
        case 0xA3: { int addr = izx(); a = read(addr); x = a; setZN(a); cycles += 6; } break;
        case 0xA7: { int addr = zp(); a = read(addr); x = a; setZN(a); cycles += 3; } break;
        case 0xAF: { int addr = absAddr(); a = read(addr); x = a; setZN(a); cycles += 4; } break;
        case 0xB3: { int addr = izy(); a = read(addr); x = a; setZN(a); cycles += 5 + (pageCrossed ? 1 : 0); } break;
        case 0xB7: { int addr = zpy(); a = read(addr); x = a; setZN(a); cycles += 4; } break;
        case 0xBF: { int addr = aby(); a = read(addr); x = a; setZN(a); cycles += 4 + (pageCrossed ? 1 : 0); } break;

        // SAX (unofficial)
        case 0x83: { int addr = izx(); write(addr, a & x); cycles += 6; } break;
        case 0x87: { int addr = zp(); write(addr, a & x); cycles += 3; } break;
        case 0x8F: { int addr = absAddr(); write(addr, a & x); cycles += 4; } break;
        case 0x97: { int addr = zpy(); write(addr, a & x); cycles += 4; } break;

        // DCP (unofficial)
        case 0xC3: { int addr = izx(); dcp(addr); cycles += 8; } break;
        case 0xC7: { int addr = zp(); dcp(addr); cycles += 5; } break;
        case 0xCF: { int addr = absAddr(); dcp(addr); cycles += 6; } break;
        case 0xD3: { int addr = izy(); dcp(addr); cycles += 8; } break;
        case 0xD7: { int addr = zpx(); dcp(addr); cycles += 6; } break;
        case 0xDB: { int addr = aby(); dcp(addr); cycles += 7; } break;
        case 0xDF: { int addr = abx(); dcp(addr); cycles += 7; } break;

        // ISB/ISC (unofficial)
        case 0xE3: { int addr = izx(); isb(addr); cycles += 8; } break;
        case 0xE7: { int addr = zp(); isb(addr); cycles += 5; } break;
        case 0xEF: { int addr = absAddr(); isb(addr); cycles += 6; } break;
        case 0xF3: { int addr = izy(); isb(addr); cycles += 8; } break;
        case 0xF7: { int addr = zpx(); isb(addr); cycles += 6; } break;
        case 0xFB: { int addr = aby(); isb(addr); cycles += 7; } break;
        case 0xFF: { int addr = abx(); isb(addr); cycles += 7; } break;

        // SLO (unofficial)
        case 0x03: { int addr = izx(); slo(addr); cycles += 8; } break;
        case 0x07: { int addr = zp(); slo(addr); cycles += 5; } break;
        case 0x0F: { int addr = absAddr(); slo(addr); cycles += 6; } break;
        case 0x13: { int addr = izy(); slo(addr); cycles += 8; } break;
        case 0x17: { int addr = zpx(); slo(addr); cycles += 6; } break;
        case 0x1B: { int addr = aby(); slo(addr); cycles += 7; } break;
        case 0x1F: { int addr = abx(); slo(addr); cycles += 7; } break;

        // RLA (unofficial)
        case 0x23: { int addr = izx(); rla(addr); cycles += 8; } break;
        case 0x27: { int addr = zp(); rla(addr); cycles += 5; } break;
        case 0x2F: { int addr = absAddr(); rla(addr); cycles += 6; } break;
        case 0x33: { int addr = izy(); rla(addr); cycles += 8; } break;
        case 0x37: { int addr = zpx(); rla(addr); cycles += 6; } break;
        case 0x3B: { int addr = aby(); rla(addr); cycles += 7; } break;
        case 0x3F: { int addr = abx(); rla(addr); cycles += 7; } break;

        // SRE (unofficial)
        case 0x43: { int addr = izx(); sre(addr); cycles += 8; } break;
        case 0x47: { int addr = zp(); sre(addr); cycles += 5; } break;
        case 0x4F: { int addr = absAddr(); sre(addr); cycles += 6; } break;
        case 0x53: { int addr = izy(); sre(addr); cycles += 8; } break;
        case 0x57: { int addr = zpx(); sre(addr); cycles += 6; } break;
        case 0x5B: { int addr = aby(); sre(addr); cycles += 7; } break;
        case 0x5F: { int addr = abx(); sre(addr); cycles += 7; } break;

        // RRA (unofficial)
        case 0x63: { int addr = izx(); rra(addr); cycles += 8; } break;
        case 0x67: { int addr = zp(); rra(addr); cycles += 5; } break;
        case 0x6F: { int addr = absAddr(); rra(addr); cycles += 6; } break;
        case 0x73: { int addr = izy(); rra(addr); cycles += 8; } break;
        case 0x77: { int addr = zpx(); rra(addr); cycles += 6; } break;
        case 0x7B: { int addr = aby(); rra(addr); cycles += 7; } break;
        case 0x7F: { int addr = abx(); rra(addr); cycles += 7; } break;

        default: cycles += 2; break;
    }
}

} // namespace nesemu
