package com.watchemu.app.nes

class Rom(data: ByteArray) {
    val prgRom: ByteArray
    val chrRom: ByteArray
    val mapperNumber: Int
    val mirroring: Int
    val hasChrRam: Boolean

    companion object {
        const val MIRROR_HORIZONTAL = 0
        const val MIRROR_VERTICAL = 1
        const val MIRROR_FOUR_SCREEN = 2
    }

    init {
        require(data.size >= 16) { "ROM too small" }
        require(
            data[0] == 0x4E.toByte() && data[1] == 0x45.toByte() &&
            data[2] == 0x53.toByte() && data[3] == 0x1A.toByte()
        ) { "Not a valid iNES ROM" }

        val prgBanks = data[4].toInt() and 0xFF
        val chrBanks = data[5].toInt() and 0xFF
        val flags6 = data[6].toInt() and 0xFF
        val flags7 = data[7].toInt() and 0xFF

        mapperNumber = ((flags7 and 0xF0)) or ((flags6 and 0xF0) shr 4)
        mirroring = when {
            flags6 and 0x08 != 0 -> MIRROR_FOUR_SCREEN
            flags6 and 0x01 != 0 -> MIRROR_VERTICAL
            else -> MIRROR_HORIZONTAL
        }

        val hasTrainer = flags6 and 0x04 != 0
        val headerSize = 16 + if (hasTrainer) 512 else 0

        val prgSize = prgBanks * 16384
        val chrSize = chrBanks * 8192

        prgRom = data.copyOfRange(headerSize, headerSize + prgSize)
        hasChrRam = chrSize == 0
        chrRom = if (chrSize > 0) {
            data.copyOfRange(headerSize + prgSize, headerSize + prgSize + chrSize)
        } else {
            ByteArray(8192) // CHR RAM
        }
    }
}
