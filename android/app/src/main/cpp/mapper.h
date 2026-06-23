#pragma once
#include <cstdint>
#include <cstring>
#include "rom.h"
#include "serialize.h"

namespace nesemu {

class Mapper {
public:
    Rom* rom;
    int mirroring;
    bool irqPending = false;

    explicit Mapper(Rom* r) : rom(r), mirroring(r->mirroring) {}
    virtual ~Mapper() {}

    virtual int cpuRead(int addr) = 0;
    virtual void cpuWrite(int addr, int value) = 0;
    virtual int chrRead(int addr) = 0;
    virtual void chrWrite(int addr, int value) = 0;
    virtual void step(int scanline, int cycle) {}

    virtual void saveState(ByteWriter& out) {
        out.i32(mirroring);
        out.boolean(irqPending);
    }
    virtual void loadState(ByteReader& in) {
        mirroring = in.i32();
        irqPending = in.boolean();
    }
};

// NROM — no bank switching
class Mapper0 : public Mapper {
    int prgMask;
public:
    explicit Mapper0(Rom* r) : Mapper(r), prgMask(int(r->prgRom.size()) - 1) {}

    int cpuRead(int addr) override {
        if (addr >= 0x8000) return rom->prgRom[addr & prgMask];
        return 0;
    }
    void cpuWrite(int, int) override {}
    int chrRead(int addr) override { return rom->chrRom[addr & 0x1FFF]; }
    void chrWrite(int addr, int value) override {
        if (rom->hasChrRam) rom->chrRom[addr & 0x1FFF] = uint8_t(value);
    }
    void saveState(ByteWriter& out) override {
        Mapper::saveState(out);
        if (rom->hasChrRam) out.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
    void loadState(ByteReader& in) override {
        Mapper::loadState(in);
        if (rom->hasChrRam) in.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
};

// MMC1 / SxROM
class Mapper1 : public Mapper {
    int shiftReg = 0x10;
    int control = 0x0C;
    int chrBank0 = 0;
    int chrBank1 = 0;
    int prgBank = 0;
    uint8_t prgRam[8192] = {0};

    int prgBankOffset(int bank) {
        int b = bank % (int(rom->prgRom.size()) / 0x4000);
        return b * 0x4000;
    }
public:
    explicit Mapper1(Rom* r) : Mapper(r) {}

    int cpuRead(int addr) override {
        if (addr < 0x6000) return 0;
        if (addr < 0x8000) return prgRam[addr - 0x6000];
        if (addr < 0xC000) {
            int mode = (control >> 2) & 3;
            int offset;
            switch (mode) {
                case 0: case 1: offset = prgBankOffset(prgBank & 0x0E); break;
                case 2: offset = 0; break;
                case 3: offset = prgBankOffset(prgBank & 0x0F); break;
                default: offset = 0; break;
            }
            return rom->prgRom[offset + (addr - 0x8000)];
        }
        int mode = (control >> 2) & 3;
        int offset;
        switch (mode) {
            case 0: case 1: offset = prgBankOffset((prgBank & 0x0E) | 1); break;
            case 2: offset = prgBankOffset(prgBank & 0x0F); break;
            case 3: offset = prgBankOffset(int(rom->prgRom.size()) / 0x4000 - 1); break;
            default: offset = 0; break;
        }
        return rom->prgRom[offset + (addr - 0xC000)];
    }

    void cpuWrite(int addr, int value) override {
        if (addr < 0x6000) return;
        if (addr < 0x8000) { prgRam[addr - 0x6000] = uint8_t(value); return; }
        if (value & 0x80) {
            shiftReg = 0x10;
            control = control | 0x0C;
            return;
        }
        bool complete = (shiftReg & 1) == 1;
        shiftReg = shiftReg >> 1;
        shiftReg = shiftReg | ((value & 1) << 4);
        if (complete) {
            int reg = (addr >> 13) & 3;
            switch (reg) {
                case 0:
                    control = shiftReg;
                    switch (control & 3) {
                        case 2: mirroring = MIRROR_VERTICAL; break;
                        case 3: mirroring = MIRROR_HORIZONTAL; break;
                        default: mirroring = control & 3; break;
                    }
                    break;
                case 1: chrBank0 = shiftReg; break;
                case 2: chrBank1 = shiftReg; break;
                case 3: prgBank = shiftReg & 0x0F; break;
            }
            shiftReg = 0x10;
        }
    }

    int chrRead(int addr) override {
        int chrSize = int(rom->chrRom.size());
        if (chrSize == 0) return 0;
        bool mode4k = (control & 0x10) != 0;
        int offset;
        if (!mode4k) {
            offset = (chrBank0 & 0x1E) * 0x1000 + addr;
        } else if (addr < 0x1000) {
            offset = chrBank0 * 0x1000 + addr;
        } else {
            offset = chrBank1 * 0x1000 + (addr - 0x1000);
        }
        return rom->chrRom[offset % chrSize];
    }

    void chrWrite(int addr, int value) override {
        if (rom->hasChrRam) rom->chrRom[addr & 0x1FFF] = uint8_t(value);
    }

    void saveState(ByteWriter& out) override {
        Mapper::saveState(out);
        out.bytes(prgRam, sizeof(prgRam));
        out.i32(shiftReg); out.i32(control);
        out.i32(chrBank0); out.i32(chrBank1); out.i32(prgBank);
        if (rom->hasChrRam) out.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
    void loadState(ByteReader& in) override {
        Mapper::loadState(in);
        in.bytes(prgRam, sizeof(prgRam));
        shiftReg = in.i32(); control = in.i32();
        chrBank0 = in.i32(); chrBank1 = in.i32(); prgBank = in.i32();
        if (rom->hasChrRam) in.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
};

// UxROM
class Mapper2 : public Mapper {
    int prgBank = 0;
    int lastBank;
public:
    explicit Mapper2(Rom* r) : Mapper(r), lastBank(int(r->prgRom.size()) / 0x4000 - 1) {}

    int cpuRead(int addr) override {
        if (addr < 0x8000) return 0;
        if (addr < 0xC000) return rom->prgRom[prgBank * 0x4000 + (addr - 0x8000)];
        return rom->prgRom[lastBank * 0x4000 + (addr - 0xC000)];
    }
    void cpuWrite(int addr, int value) override {
        if (addr >= 0x8000) prgBank = value % (int(rom->prgRom.size()) / 0x4000);
    }
    int chrRead(int addr) override { return rom->chrRom[addr & 0x1FFF]; }
    void chrWrite(int addr, int value) override {
        if (rom->hasChrRam) rom->chrRom[addr & 0x1FFF] = uint8_t(value);
    }
    void saveState(ByteWriter& out) override {
        Mapper::saveState(out);
        out.i32(prgBank);
        if (rom->hasChrRam) out.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
    void loadState(ByteReader& in) override {
        Mapper::loadState(in);
        prgBank = in.i32();
        if (rom->hasChrRam) in.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
};

// CNROM
class Mapper3 : public Mapper {
    int chrBank = 0;
    int prgMask;
public:
    explicit Mapper3(Rom* r) : Mapper(r), prgMask(int(r->prgRom.size()) - 1) {}

    int cpuRead(int addr) override {
        if (addr >= 0x8000) return rom->prgRom[addr & prgMask];
        return 0;
    }
    void cpuWrite(int addr, int value) override {
        if (addr >= 0x8000) chrBank = value & 3;
    }
    int chrRead(int addr) override {
        int offset = chrBank * 0x2000 + addr;
        return rom->chrRom[offset % int(rom->chrRom.size())];
    }
    void chrWrite(int addr, int value) override {
        if (rom->hasChrRam) rom->chrRom[addr & 0x1FFF] = uint8_t(value);
    }
    void saveState(ByteWriter& out) override {
        Mapper::saveState(out);
        out.i32(chrBank);
        if (rom->hasChrRam) out.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
    void loadState(ByteReader& in) override {
        Mapper::loadState(in);
        chrBank = in.i32();
        if (rom->hasChrRam) in.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
};

// MMC3 / TxROM
class Mapper4 : public Mapper {
    uint8_t prgRam[8192] = {0};
    int regs[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    int bankSelect = 0;
    int irqCounter = 0;
    int irqLatch = 0;
    bool irqEnabled = false;
    bool irqReload = false;
    int prgMode = 0;
    int chrMode = 0;
    int prgBankCount;

    int chrBankFor(int addr) {
        int slot = addr / 0x0400;
        if (chrMode == 0) {
            switch (slot) {
                case 0: return regs[0] & 0xFE;
                case 1: return regs[0] | 1;
                case 2: return regs[1] & 0xFE;
                case 3: return regs[1] | 1;
                case 4: return regs[2];
                case 5: return regs[3];
                case 6: return regs[4];
                case 7: return regs[5];
                default: return 0;
            }
        } else {
            switch (slot) {
                case 0: return regs[2];
                case 1: return regs[3];
                case 2: return regs[4];
                case 3: return regs[5];
                case 4: return regs[0] & 0xFE;
                case 5: return regs[0] | 1;
                case 6: return regs[1] & 0xFE;
                case 7: return regs[1] | 1;
                default: return 0;
            }
        }
    }
public:
    explicit Mapper4(Rom* r) : Mapper(r), prgBankCount(int(r->prgRom.size()) / 0x2000) {}

    int cpuRead(int addr) override {
        if (addr < 0x6000) return 0;
        if (addr < 0x8000) return prgRam[addr - 0x6000];
        if (addr < 0xA000) {
            int bank = (prgMode == 0) ? regs[6] % prgBankCount : (prgBankCount - 2);
            return rom->prgRom[bank * 0x2000 + (addr - 0x8000)];
        }
        if (addr < 0xC000) {
            int bank = regs[7] % prgBankCount;
            return rom->prgRom[bank * 0x2000 + (addr - 0xA000)];
        }
        if (addr < 0xE000) {
            int bank = (prgMode == 0) ? (prgBankCount - 2) : regs[6] % prgBankCount;
            return rom->prgRom[bank * 0x2000 + (addr - 0xC000)];
        }
        int bank = prgBankCount - 1;
        return rom->prgRom[bank * 0x2000 + (addr - 0xE000)];
    }

    void cpuWrite(int addr, int value) override {
        if (addr < 0x6000) return;
        if (addr < 0x8000) { prgRam[addr - 0x6000] = uint8_t(value); return; }
        if (addr < 0xA000) {
            if ((addr & 1) == 0) {
                bankSelect = value & 7;
                prgMode = (value >> 6) & 1;
                chrMode = (value >> 7) & 1;
            } else {
                regs[bankSelect] = value;
            }
        } else if (addr < 0xC000) {
            if ((addr & 1) == 0) {
                mirroring = (value & 1) == 0 ? MIRROR_VERTICAL : MIRROR_HORIZONTAL;
            }
        } else if (addr < 0xE000) {
            if ((addr & 1) == 0) irqLatch = value;
            else irqReload = true;
        } else {
            if ((addr & 1) == 0) { irqEnabled = false; irqPending = false; }
            else irqEnabled = true;
        }
    }

    int chrRead(int addr) override {
        int chrSize = int(rom->chrRom.size());
        if (chrSize == 0) return 0;
        int bank = chrBankFor(addr);
        return rom->chrRom[(bank * 0x0400 + (addr & 0x03FF)) % chrSize];
    }
    void chrWrite(int addr, int value) override {
        if (rom->hasChrRam) rom->chrRom[addr & 0x1FFF] = uint8_t(value);
    }

    void step(int scanline, int cycle) override {
        if (cycle != 260) return;
        if (scanline > 239 && scanline != 261) return;
        if (irqReload || irqCounter == 0) {
            irqCounter = irqLatch;
            irqReload = false;
        } else {
            irqCounter--;
        }
        if (irqCounter == 0 && irqEnabled) irqPending = true;
    }

    void saveState(ByteWriter& out) override {
        Mapper::saveState(out);
        out.bytes(prgRam, sizeof(prgRam));
        for (int r : regs) out.i32(r);
        out.i32(bankSelect); out.i32(irqCounter); out.i32(irqLatch);
        out.boolean(irqEnabled); out.boolean(irqReload);
        out.i32(prgMode); out.i32(chrMode);
        if (rom->hasChrRam) out.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
    void loadState(ByteReader& in) override {
        Mapper::loadState(in);
        in.bytes(prgRam, sizeof(prgRam));
        for (int i = 0; i < 8; i++) regs[i] = in.i32();
        bankSelect = in.i32(); irqCounter = in.i32(); irqLatch = in.i32();
        irqEnabled = in.boolean(); irqReload = in.boolean();
        prgMode = in.i32(); chrMode = in.i32();
        if (rom->hasChrRam) in.bytes(rom->chrRom.data(), rom->chrRom.size());
    }
};

inline Mapper* createMapper(Rom* rom) {
    switch (rom->mapperNumber) {
        case 0: return new Mapper0(rom);
        case 1: return new Mapper1(rom);
        case 2: return new Mapper2(rom);
        case 3: return new Mapper3(rom);
        case 4: return new Mapper4(rom);
        default: return nullptr;
    }
}

} // namespace nesemu
