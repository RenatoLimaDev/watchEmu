package com.watchemu.app.nes

object Palette {
    // Standard 2C02 NES palette — 64 colors as 0xAARRGGBB
    val COLORS = intArrayOf(
        0xFF666666.toInt(), 0xFF002A88.toInt(), 0xFF1412A7.toInt(), 0xFF3B00A4.toInt(),
        0xFF5C007E.toInt(), 0xFF6E0040.toInt(), 0xFF6C0600.toInt(), 0xFF561D00.toInt(),
        0xFF333500.toInt(), 0xFF0B4800.toInt(), 0xFF005200.toInt(), 0xFF004F08.toInt(),
        0xFF00404D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFADADAD.toInt(), 0xFF155FD9.toInt(), 0xFF4240FF.toInt(), 0xFF7527FE.toInt(),
        0xFFA01ACC.toInt(), 0xFFB71E7B.toInt(), 0xFFB53120.toInt(), 0xFF994E00.toInt(),
        0xFF6B6D00.toInt(), 0xFF388700.toInt(), 0xFF0C9300.toInt(), 0xFF008F32.toInt(),
        0xFF007C8D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFFFFEFF.toInt(), 0xFF64B0FF.toInt(), 0xFF9290FF.toInt(), 0xFFC676FF.toInt(),
        0xFFF36AFF.toInt(), 0xFFFE6ECC.toInt(), 0xFFFE8170.toInt(), 0xFFEA9E22.toInt(),
        0xFFBCBE00.toInt(), 0xFF88D800.toInt(), 0xFF5CE430.toInt(), 0xFF45E082.toInt(),
        0xFF48CDDE.toInt(), 0xFF4F4F4F.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),

        0xFFFFFEFF.toInt(), 0xFFC0DFFF.toInt(), 0xFFD3D2FF.toInt(), 0xFFE8C8FF.toInt(),
        0xFFFBC2FF.toInt(), 0xFFFEC4EA.toInt(), 0xFFFECCC5.toInt(), 0xFFF7D8A5.toInt(),
        0xFFE4E594.toInt(), 0xFFCFEF96.toInt(), 0xFFBDF4AB.toInt(), 0xFFB3F3CC.toInt(),
        0xFFB5EBF2.toInt(), 0xFFB8B8B8.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()
    )
}
