#pragma once
#include <cstdint>
#include "serialize.h"
#include "palette.h"

namespace nesemu {

class Nes;

class Ppu {
public:
    static const int WIDTH = 256;
    static const int HEIGHT = 240;

    Nes* nes;

    uint32_t frameBuffer[WIDTH * HEIGHT];

    uint8_t nametable[2048];
    uint8_t paletteRam[32];
    uint8_t oam[256];

    int ppuCtrl = 0, ppuMask = 0, ppuStatus = 0, oamAddr = 0;
    int v = 0, t = 0, fineX = 0;
    bool writeToggle = false;

    int scanline = 0, cycle = 0;
    bool frameComplete = false;
    bool nmiTriggered = false;
    bool oddFrame = false;
    int dataBuffer = 0;

    int bgPixel[WIDTH];
    int bgPalette[WIDTH];

    // Preallocated sprite scanline buffers (max 8 sprites/line)
    int spriteIndex[8], spriteX[8], spriteAttr[8], spriteLo[8], spriteHi[8];

    // Resolved ARGB palette cache, rebuilt only on palette writes.
    uint32_t paletteColor[32];

    explicit Ppu(Nes* n) : nes(n) {
        for (int i = 0; i < 2048; i++) nametable[i] = 0;
        for (int i = 0; i < 32; i++) paletteRam[i] = 0;
        for (int i = 0; i < 256; i++) oam[i] = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) frameBuffer[i] = 0;
        updatePaletteCache();
    }

    void reset();
    int readRegister(int addr);
    void writeRegister(int addr, int value);
    void writeOamDma(const uint8_t* data, int startAddr);
    void step();
    void saveState(ByteWriter& out);
    void loadState(ByteReader& in);

    // Trivial, Nes-independent helpers (safe to inline here).
    bool renderEnabled() const { return (ppuMask & 0x18) != 0; }
    bool showBg() const { return (ppuMask & 0x08) != 0; }
    bool showSprites() const { return (ppuMask & 0x10) != 0; }
    bool showBgLeft() const { return (ppuMask & 0x02) != 0; }
    bool showSpritesLeft() const { return (ppuMask & 0x04) != 0; }

    int paletteIndex(int addr) const {
        int i = addr & 0x1F;
        if (i >= 16 && (i & 3) == 0) i -= 16;
        return i;
    }
    void updatePaletteCache() {
        for (int i = 0; i < 32; i++)
            paletteColor[i] = PALETTE[paletteRam[paletteIndex(i)] & 0x3F];
    }
    static int reverseBits(int b) {
        int v = b & 0xFF;
        v = ((v & 0xF0) >> 4) | ((v & 0x0F) << 4);
        v = ((v & 0xCC) >> 2) | ((v & 0x33) << 2);
        v = ((v & 0xAA) >> 1) | ((v & 0x55) << 1);
        return v;
    }

private:
    int ppuRead(int addr);
    void ppuWrite(int addr, int value);
    int mirrorAddr(int addr);
    void renderScanline();
    void renderBgScanline();
    void renderSpriteScanline();
    void incrementY();
    void copyHorizontal();
    void copyVertical();
};

} // namespace nesemu
