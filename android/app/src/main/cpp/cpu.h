#pragma once
#include <cstdint>

namespace nesemu {

class Nes;

// MOS 6502 core. Memory accesses go through the owning Nes bus. The page-crossed
// flag from indexed addressing is stored in a field (not returned) to avoid any
// per-instruction allocation/boxing.
class Cpu {
public:
    Nes* nes;

    int a = 0, x = 0, y = 0;
    int sp = 0xFD, pc = 0;
    int cycles = 0;

    bool flagC = false, flagZ = false, flagI = true, flagD = false;
    bool flagB = false, flagV = false, flagN = false;

    int stallCycles = 0;
    bool pageCrossed = false;

    explicit Cpu(Nes* n) : nes(n) {}

    void reset();
    int step();

private:
    int read(int addr);
    void write(int addr, int value);
    int read16(int addr);
    int read16Bug(int addr);

    void push(int value);
    void push16(int value);
    int pull();
    int pull16();

    int packStatus(bool brk = false);
    void unpackStatus(int s);
    void setZN(int value);

    void nmi();
    void irq();

    static bool pageCross(int a, int b) { return (a & 0xFF00) != (b & 0xFF00); }

    void execute(int op);

    int imm();
    int zp();
    int zpx();
    int zpy();
    int absAddr();
    int abx();
    int aby();
    int izx();
    int izy();

    void branch(bool condition);

    void adc(int value);
    void sbc(int value);
    void cmp(int reg, int value);
    void bitOp(int value);

    int aslAcc(int v);
    void aslMem(int addr);
    int lsrAcc(int v);
    void lsrMem(int addr);
    int rolAcc(int v);
    void rolMem(int addr);
    int rorAcc(int v);
    void rorMem(int addr);
    void decMem(int addr);
    void incMem(int addr);

    void dcp(int addr);
    void isb(int addr);
    void slo(int addr);
    void rla(int addr);
    void sre(int addr);
    void rra(int addr);
};

} // namespace nesemu
