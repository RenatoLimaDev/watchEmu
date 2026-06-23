#pragma once
#include <cstdint>
#include <cstring>
#include <vector>
#include "serialize.h"
#include "rom.h"
#include "controller.h"
#include "mapper.h"
#include "apu.h"
#include "cpu.h"
#include "ppu.h"

namespace nesemu {

class Nes {
public:
    Rom rom;
    Mapper* mapper = nullptr;

    Cpu cpu;
    Ppu ppu;
    Apu apu;
    Controller controller1, controller2;

    uint8_t ram[2048];
    bool romLoaded = false;
    bool nmiPending = false;

    Nes() : cpu(this), ppu(this) {
        std::memset(ram, 0, sizeof(ram));
    }
    ~Nes() { delete mapper; }

    bool irqPending() { return mapper && mapper->irqPending && !cpu.flagI; }

    bool loadRom(const uint8_t* data, size_t len) {
        if (!rom.parse(data, len)) return false;
        delete mapper;
        mapper = createMapper(&rom);
        if (!mapper) return false;
        reset();
        romLoaded = true;
        return true;
    }

    void reset() {
        std::memset(ram, 0, sizeof(ram));
        ppu.reset();
        cpu.reset();
        apu.reset();
        nmiPending = false;
    }

    int cpuRead(int addr) {
        if (addr < 0x2000) return ram[addr & 0x07FF];
        if (addr < 0x4000) return ppu.readRegister(addr & 7);
        if (addr == 0x4015) return apu.readStatus();
        if (addr == 0x4016) return controller1.read();
        if (addr == 0x4017) return controller2.read();
        if (addr >= 0x4020) return mapper->cpuRead(addr);
        return 0;
    }

    void cpuWrite(int addr, int value) {
        if (addr < 0x2000) {
            ram[addr & 0x07FF] = uint8_t(value);
        } else if (addr < 0x4000) {
            ppu.writeRegister(addr & 7, value);
        } else if (addr == 0x4014) {
            int page = value << 8;
            uint8_t data[256];
            for (int i = 0; i < 256; i++) data[i] = uint8_t(cpuRead(page + i));
            ppu.writeOamDma(data, 0);
            cpu.stallCycles += 513 + ((cpu.cycles % 2 == 1) ? 1 : 0);
        } else if (addr == 0x4016) {
            controller1.write(value);
            controller2.write(value);
        } else if ((addr >= 0x4000 && addr <= 0x4015) || addr == 0x4017) {
            apu.writeRegister(addr, value);
        } else if (addr >= 0x4020) {
            mapper->cpuWrite(addr, value);
        }
    }

    // Run one CPU instruction plus the matching PPU/APU cycles.
    int stepInstruction() {
        int cpuCycles = cpu.step();
        for (int c = 0; c < cpuCycles; c++) apu.stepFrameCounter();
        int ppuCycles = cpuCycles * 3;
        for (int i = 0; i < ppuCycles; i++) {
            ppu.step();
            if (ppu.nmiTriggered) {
                ppu.nmiTriggered = false;
                nmiPending = true;
            }
        }
        return cpuCycles;
    }

    void buttonDown(int button) { controller1.setButton(button, true); }
    void buttonUp(int button) { controller1.setButton(button, false); }

    std::vector<uint8_t> saveState() {
        ByteWriter w;
        w.i32(0x57454D55); // magic "WEMU"
        w.bytes(ram, sizeof(ram));

        w.i32(cpu.a); w.i32(cpu.x); w.i32(cpu.y);
        w.i32(cpu.sp); w.i32(cpu.pc); w.i32(cpu.cycles);
        w.i32(cpu.stallCycles);
        w.boolean(cpu.flagC); w.boolean(cpu.flagZ); w.boolean(cpu.flagI);
        w.boolean(cpu.flagD); w.boolean(cpu.flagB);
        w.boolean(cpu.flagV); w.boolean(cpu.flagN);

        ppu.saveState(w);
        apu.saveState(w);
        if (mapper) mapper->saveState(w);
        w.boolean(nmiPending);
        return std::move(w.buf);
    }

    bool loadState(const uint8_t* data, size_t len) {
        ByteReader r(data, len);
        int magic = r.i32();
        if (magic != 0x57454D55) return false;
        r.bytes(ram, sizeof(ram));

        cpu.a = r.i32(); cpu.x = r.i32(); cpu.y = r.i32();
        cpu.sp = r.i32(); cpu.pc = r.i32(); cpu.cycles = r.i32();
        cpu.stallCycles = r.i32();
        cpu.flagC = r.boolean(); cpu.flagZ = r.boolean(); cpu.flagI = r.boolean();
        cpu.flagD = r.boolean(); cpu.flagB = r.boolean();
        cpu.flagV = r.boolean(); cpu.flagN = r.boolean();

        ppu.loadState(r);
        apu.loadState(r);
        if (mapper) mapper->loadState(r);
        nmiPending = r.boolean();
        return r.ok;
    }
};

} // namespace nesemu

// Method bodies that depend on the fully-defined Nes (single translation unit
// so -O3 can inline the memory bus into the CPU/PPU hot loops).
#include "cpu_impl.h"
#include "ppu_impl.h"
