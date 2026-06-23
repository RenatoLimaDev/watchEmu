#pragma once
#include "ppu.h"
#include "nes.h"

namespace nesemu {

inline void Ppu::reset() {
    ppuCtrl = 0; ppuMask = 0; ppuStatus = 0; oamAddr = 0;
    v = 0; t = 0; fineX = 0; writeToggle = false;
    scanline = 0; cycle = 0; frameComplete = false; oddFrame = false;
    updatePaletteCache();
}

inline int Ppu::ppuRead(int addr) {
    int a = addr & 0x3FFF;
    if (a < 0x2000) return nes->mapper->chrRead(a);
    if (a < 0x3F00) return nametable[mirrorAddr(a)];
    return paletteRam[paletteIndex(a)];
}

inline void Ppu::ppuWrite(int addr, int value) {
    int a = addr & 0x3FFF;
    if (a < 0x2000) {
        nes->mapper->chrWrite(a, value);
    } else if (a < 0x3F00) {
        nametable[mirrorAddr(a)] = uint8_t(value);
    } else {
        paletteRam[paletteIndex(a)] = uint8_t(value & 0x3F);
        updatePaletteCache();
    }
}

inline int Ppu::mirrorAddr(int addr) {
    int a = (addr - 0x2000) & 0x0FFF;
    switch (nes->mapper->mirroring) {
        case MIRROR_VERTICAL: return a & 0x07FF;
        case MIRROR_HORIZONTAL: return (a < 0x0800) ? (a & 0x03FF) : (0x0400 + (a & 0x03FF));
        default: return a & 0x07FF;
    }
}

inline int Ppu::readRegister(int addr) {
    switch (addr) {
        case 2: {
            int s = (ppuStatus & 0xE0) | (dataBuffer & 0x1F);
            ppuStatus = ppuStatus & 0x7F;
            writeToggle = false;
            return s;
        }
        case 4:
            return oam[oamAddr];
        case 7: {
            int data = ppuRead(v & 0x3FFF);
            if ((v & 0x3FFF) < 0x3F00) {
                int buffered = dataBuffer; dataBuffer = data; data = buffered;
            } else {
                dataBuffer = ppuRead((v - 0x1000) & 0x3FFF);
            }
            v = (v + ((ppuCtrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
            return data;
        }
        default:
            return 0;
    }
}

inline void Ppu::writeRegister(int addr, int value) {
    switch (addr) {
        case 0: ppuCtrl = value; t = (t & 0x73FF) | ((value & 3) << 10); break;
        case 1: ppuMask = value; break;
        case 3: oamAddr = value; break;
        case 4: oam[oamAddr] = uint8_t(value); oamAddr = (oamAddr + 1) & 0xFF; break;
        case 5:
            if (!writeToggle) {
                t = (t & 0x7FE0) | (value >> 3); fineX = value & 7;
            } else {
                t = (t & 0x0C1F) | ((value & 7) << 12) | ((value & 0xF8) << 2);
            }
            writeToggle = !writeToggle;
            break;
        case 6:
            if (!writeToggle) {
                t = (t & 0x00FF) | ((value & 0x3F) << 8);
            } else {
                t = (t & 0xFF00) | value; v = t;
            }
            writeToggle = !writeToggle;
            break;
        case 7:
            ppuWrite(v & 0x3FFF, value);
            v = (v + ((ppuCtrl & 0x04) != 0 ? 32 : 1)) & 0x7FFF;
            break;
    }
}

inline void Ppu::writeOamDma(const uint8_t* data, int startAddr) {
    for (int i = 0; i < 256; i++) oam[(oamAddr + i) & 0xFF] = data[startAddr + i];
}

inline void Ppu::step() {
    nes->mapper->step(scanline, cycle);

    if (scanline < 240) {
        if (cycle == 257 && renderEnabled()) copyHorizontal();
        if (cycle == 340) renderScanline();
    }

    if (scanline == 241 && cycle == 1) {
        ppuStatus = ppuStatus | 0x80;
        frameComplete = true;
        if ((ppuCtrl & 0x80) != 0) nmiTriggered = true;
    }

    if (scanline == 261) {
        if (cycle == 1) ppuStatus = ppuStatus & 0x1F;
        if (cycle >= 280 && cycle <= 304 && renderEnabled()) copyVertical();
        if (cycle == 257 && renderEnabled()) copyHorizontal();
    }

    cycle++;
    if (cycle > 340) {
        cycle = 0;
        scanline++;
        if (scanline > 261) {
            scanline = 0;
            oddFrame = !oddFrame;
            if (oddFrame && renderEnabled()) cycle = 1;
        }
    }
}

inline void Ppu::renderScanline() {
    if (!renderEnabled()) {
        uint32_t bgColor = paletteColor[0];
        int offset = scanline * WIDTH;
        for (int x = 0; x < WIDTH; x++) frameBuffer[offset + x] = bgColor;
        return;
    }
    renderBgScanline();
    renderSpriteScanline();
    incrementY();
}

inline void Ppu::renderBgScanline() {
    int bgTable = (ppuCtrl & 0x10) != 0 ? 0x1000 : 0;
    int vAddr = v;
    int screenX = 0;

    for (int tile = 0; tile < 34; tile++) {
        if (screenX >= WIDTH) break;

        int fineY = (vAddr >> 12) & 7;
        int ntAddr = 0x2000 | (vAddr & 0x0FFF);
        int tileNum = ppuRead(ntAddr);

        int attrAddr = 0x23C0 | (vAddr & 0x0C00) | ((vAddr >> 4) & 0x38) | ((vAddr >> 2) & 7);
        int attrShift = ((vAddr >> 4) & 4) | (vAddr & 2);
        int palette = (ppuRead(attrAddr) >> attrShift) & 3;

        int patAddr = bgTable + tileNum * 16 + fineY;
        int lo = ppuRead(patAddr);
        int hi = ppuRead(patAddr + 8);

        int startBit = (tile == 0) ? fineX : 0;

        for (int bit = startBit; bit < 8; bit++) {
            if (screenX >= WIDTH) break;
            if (showBg() && (showBgLeft() || screenX >= 8)) {
                int shift = 7 - bit;
                int pixel = ((lo >> shift) & 1) | (((hi >> shift) & 1) << 1);
                bgPixel[screenX] = pixel;
                bgPalette[screenX] = palette;
            } else {
                bgPixel[screenX] = 0;
                bgPalette[screenX] = 0;
            }
            screenX++;
        }

        if ((vAddr & 0x001F) == 31) {
            vAddr = (vAddr & 0x7FE0) ^ 0x0400;
        } else {
            vAddr++;
        }
    }
}

inline void Ppu::renderSpriteScanline() {
    int spriteHeight = (ppuCtrl & 0x20) != 0 ? 16 : 8;
    int offset = scanline * WIDTH;

    int count = 0;
    for (int i = 0; i < 64; i++) {
        if (count >= 8) break;
        int y = oam[i * 4] + 1;
        int row = scanline - y;
        if (row < 0 || row >= spriteHeight) continue;

        int tileIndex = oam[i * 4 + 1];
        int attr = oam[i * 4 + 2];
        int sx = oam[i * 4 + 3];
        bool flipH = (attr & 0x40) != 0;
        bool flipV = (attr & 0x80) != 0;

        int tileRow = flipV ? spriteHeight - 1 - row : row;
        int patternAddr;
        if (spriteHeight == 8) {
            int sprTable = (ppuCtrl & 0x08) != 0 ? 0x1000 : 0;
            patternAddr = sprTable + tileIndex * 16 + tileRow;
        } else {
            int table = (tileIndex & 1) * 0x1000;
            int tile = tileIndex & 0xFE;
            if (tileRow >= 8) { tile++; tileRow -= 8; }
            patternAddr = table + tile * 16 + tileRow;
        }

        int lo = ppuRead(patternAddr);
        int hi = ppuRead(patternAddr + 8);
        if (flipH) { lo = reverseBits(lo); hi = reverseBits(hi); }

        spriteIndex[count] = i;
        spriteX[count] = sx;
        spriteAttr[count] = attr;
        spriteLo[count] = lo;
        spriteHi[count] = hi;
        count++;
    }

    uint32_t bgColor = paletteColor[0];
    for (int x = 0; x < WIDTH; x++) {
        int bg = bgPixel[x];
        uint32_t color = (bg != 0) ? paletteColor[bgPalette[x] * 4 + bg] : bgColor;

        if (showSprites() && (showSpritesLeft() || x >= 8)) {
            for (int s = 0; s < count; s++) {
                int sx = spriteX[s];
                if (x < sx || x >= sx + 8) continue;
                int bit = 7 - (x - sx);
                int pixel = ((spriteLo[s] >> bit) & 1) | (((spriteHi[s] >> bit) & 1) << 1);
                if (pixel == 0) continue;

                if (spriteIndex[s] == 0 && bg != 0 && x < 255) {
                    ppuStatus = ppuStatus | 0x40;
                }

                bool behindBg = (spriteAttr[s] & 0x20) != 0;
                if (!behindBg || bg == 0) {
                    int sprPalette = (spriteAttr[s] & 3) + 4;
                    color = paletteColor[sprPalette * 4 + pixel];
                }
                break;
            }
        }

        frameBuffer[offset + x] = color;
    }
}

inline void Ppu::incrementY() {
    if (!renderEnabled()) return;
    if ((v & 0x7000) != 0x7000) {
        v += 0x1000;
    } else {
        v = v & 0x0FFF;
        int coarseY = (v & 0x03E0) >> 5;
        if (coarseY == 29) { coarseY = 0; v = v ^ 0x0800; }
        else if (coarseY == 31) { coarseY = 0; }
        else coarseY++;
        v = (v & 0x7C1F) | (coarseY << 5);
    }
}

inline void Ppu::copyHorizontal() { v = (v & 0x7BE0) | (t & 0x041F); }
inline void Ppu::copyVertical() { v = (v & 0x041F) | (t & 0x7BE0); }

inline void Ppu::saveState(ByteWriter& out) {
    out.bytes(nametable, sizeof(nametable));
    out.bytes(paletteRam, sizeof(paletteRam));
    out.bytes(oam, sizeof(oam));
    out.i32(ppuCtrl); out.i32(ppuMask); out.i32(ppuStatus); out.i32(oamAddr);
    out.i32(v); out.i32(t); out.i32(fineX);
    out.boolean(writeToggle);
    out.i32(scanline); out.i32(cycle);
    out.boolean(frameComplete); out.boolean(nmiTriggered);
    out.boolean(oddFrame); out.i32(dataBuffer);
}

inline void Ppu::loadState(ByteReader& in) {
    in.bytes(nametable, sizeof(nametable));
    in.bytes(paletteRam, sizeof(paletteRam));
    in.bytes(oam, sizeof(oam));
    ppuCtrl = in.i32(); ppuMask = in.i32(); ppuStatus = in.i32(); oamAddr = in.i32();
    v = in.i32(); t = in.i32(); fineX = in.i32();
    writeToggle = in.boolean();
    scanline = in.i32(); cycle = in.i32();
    frameComplete = in.boolean(); nmiTriggered = in.boolean();
    oddFrame = in.boolean(); dataBuffer = in.i32();
    updatePaletteCache();
}

} // namespace nesemu
