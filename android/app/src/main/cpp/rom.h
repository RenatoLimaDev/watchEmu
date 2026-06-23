#pragma once
#include <cstdint>
#include <vector>
#include <cstring>

namespace nesemu {

static const int MIRROR_HORIZONTAL = 0;
static const int MIRROR_VERTICAL = 1;
static const int MIRROR_FOUR_SCREEN = 2;

struct Rom {
    std::vector<uint8_t> prgRom;
    std::vector<uint8_t> chrRom;
    int mapperNumber = 0;
    int mirroring = MIRROR_HORIZONTAL;
    bool hasChrRam = false;
    bool valid = false;

    bool parse(const uint8_t* data, size_t len) {
        if (len < 16) return false;
        if (!(data[0] == 0x4E && data[1] == 0x45 && data[2] == 0x53 && data[3] == 0x1A))
            return false;

        int prgBanks = data[4];
        int chrBanks = data[5];
        int flags6 = data[6];
        int flags7 = data[7];

        mapperNumber = (flags7 & 0xF0) | ((flags6 & 0xF0) >> 4);
        if (flags6 & 0x08) mirroring = MIRROR_FOUR_SCREEN;
        else if (flags6 & 0x01) mirroring = MIRROR_VERTICAL;
        else mirroring = MIRROR_HORIZONTAL;

        bool hasTrainer = (flags6 & 0x04) != 0;
        size_t headerSize = 16 + (hasTrainer ? 512 : 0);

        size_t prgSize = static_cast<size_t>(prgBanks) * 16384;
        size_t chrSize = static_cast<size_t>(chrBanks) * 8192;

        if (headerSize + prgSize + chrSize > len) return false;

        prgRom.assign(data + headerSize, data + headerSize + prgSize);
        hasChrRam = (chrSize == 0);
        if (chrSize > 0) {
            chrRom.assign(data + headerSize + prgSize, data + headerSize + prgSize + chrSize);
        } else {
            chrRom.assign(8192, 0); // CHR RAM
        }
        valid = true;
        return true;
    }
};

} // namespace nesemu
