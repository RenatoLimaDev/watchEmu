package com.watchemu.app.nes

import java.io.DataInputStream
import java.io.DataOutputStream

class Ppu(private val nes: Nes) {
    companion object {
        const val WIDTH = 256
        const val HEIGHT = 240
    }

    val frameBuffer = IntArray(WIDTH * HEIGHT)

    // VRAM
    private val nametable = ByteArray(2048)
    private val paletteRam = ByteArray(32)
    private val oam = ByteArray(256)

    // Resolved-color cache: paletteCache[i] = Palette.COLORS[paletteRam[i] & 0x3F].
    // Updated only when palette RAM is written, so per-pixel rendering reads an
    // ARGB int straight from here instead of going through ppuRead/paletteIndex.
    private val paletteCache = IntArray(32)

    // Registers
    private var ppuCtrl = 0
    private var ppuMask = 0
    private var ppuStatus = 0
    private var oamAddr = 0

    // Internal registers
    private var v = 0       // current VRAM address (15 bits)
    private var t = 0       // temporary VRAM address
    private var fineX = 0   // fine X scroll (3 bits)
    private var writeToggle = false

    // Timing
    var scanline = 0; private set
    var cycle = 0; private set
    var frameComplete = false
    var nmiTriggered = false
    private var oddFrame = false
    private var dataBuffer = 0

    // Scanline rendering buffers
    private val bgPixel = IntArray(WIDTH)
    private val bgPalette = IntArray(WIDTH)

    // Per-scanline sprite buffers (max 8 sprites). Reused every scanline to
    // avoid allocating a list + objects 240x per frame (kills GC churn / FPS).
    private val sprIndex = IntArray(8)
    private val sprX = IntArray(8)
    private val sprAttr = IntArray(8)
    private val sprLo = IntArray(8)
    private val sprHi = IntArray(8)

    fun reset() {
        ppuCtrl = 0; ppuMask = 0; ppuStatus = 0; oamAddr = 0
        v = 0; t = 0; fineX = 0; writeToggle = false
        scanline = 0; cycle = 0; frameComplete = false; oddFrame = false
        rebuildPaletteCache()
    }

    // -- Register access from CPU --

    fun readRegister(addr: Int): Int = when (addr) {
        2 -> {
            val s = (ppuStatus and 0xE0) or (dataBuffer and 0x1F)
            ppuStatus = ppuStatus and 0x7F
            writeToggle = false
            s
        }
        4 -> oam[oamAddr].toInt() and 0xFF
        7 -> {
            var data = ppuRead(v and 0x3FFF)
            if (v and 0x3FFF < 0x3F00) {
                val buffered = dataBuffer; dataBuffer = data; data = buffered
            } else {
                dataBuffer = ppuRead((v - 0x1000) and 0x3FFF)
            }
            v = (v + if (ppuCtrl and 0x04 != 0) 32 else 1) and 0x7FFF
            data
        }
        else -> 0
    }

    fun writeRegister(addr: Int, value: Int) {
        when (addr) {
            0 -> { ppuCtrl = value; t = (t and 0x73FF) or ((value and 3) shl 10) }
            1 -> ppuMask = value
            3 -> oamAddr = value
            4 -> { oam[oamAddr] = value.toByte(); oamAddr = (oamAddr + 1) and 0xFF }
            5 -> {
                if (!writeToggle) {
                    t = (t and 0x7FE0) or (value shr 3); fineX = value and 7
                } else {
                    t = (t and 0x0C1F) or ((value and 7) shl 12) or ((value and 0xF8) shl 2)
                }
                writeToggle = !writeToggle
            }
            6 -> {
                if (!writeToggle) {
                    t = (t and 0x00FF) or ((value and 0x3F) shl 8)
                } else {
                    t = (t and 0xFF00) or value; v = t
                }
                writeToggle = !writeToggle
            }
            7 -> {
                ppuWrite(v and 0x3FFF, value)
                v = (v + if (ppuCtrl and 0x04 != 0) 32 else 1) and 0x7FFF
            }
        }
    }

    fun writeOamDma(data: ByteArray, startAddr: Int) {
        for (i in 0 until 256) oam[(oamAddr + i) and 0xFF] = data[startAddr + i]
    }

    // -- PPU memory bus --

    private fun ppuRead(addr: Int): Int {
        val a = addr and 0x3FFF
        return when {
            a < 0x2000 -> nes.mapper.chrRead(a)
            a < 0x3F00 -> nametable[mirrorAddr(a)].toInt() and 0xFF
            else -> paletteRam[paletteIndex(a)].toInt() and 0xFF
        }
    }

    private fun ppuWrite(addr: Int, value: Int) {
        val a = addr and 0x3FFF
        when {
            a < 0x2000 -> nes.mapper.chrWrite(a, value)
            a < 0x3F00 -> nametable[mirrorAddr(a)] = value.toByte()
            else -> {
                val idx = paletteIndex(a)
                paletteRam[idx] = (value and 0x3F).toByte()
                paletteCache[idx] = Palette.COLORS[value and 0x3F]
            }
        }
    }

    /** Rebuild the resolved-color cache from palette RAM (after loadState). */
    private fun rebuildPaletteCache() {
        for (i in 0 until 32) paletteCache[i] = Palette.COLORS[paletteRam[i].toInt() and 0x3F]
    }

    private fun paletteIndex(addr: Int): Int {
        var i = addr and 0x1F
        if (i >= 16 && i and 3 == 0) i -= 16
        return i
    }

    private fun mirrorAddr(addr: Int): Int {
        val a = (addr - 0x2000) and 0x0FFF
        return when (nes.mapper.mirroring) {
            Rom.MIRROR_VERTICAL -> a and 0x07FF
            Rom.MIRROR_HORIZONTAL ->
                if (a < 0x0800) (a and 0x03FF) else (0x0400 + (a and 0x03FF))
            else -> a and 0x07FF
        }
    }

    // -- Rendering flags --
    private val renderEnabled get() = ppuMask and 0x18 != 0
    private val showBg get() = ppuMask and 0x08 != 0
    private val showSprites get() = ppuMask and 0x10 != 0
    private val showBgLeft get() = ppuMask and 0x02 != 0
    private val showSpritesLeft get() = ppuMask and 0x04 != 0

    // -- Main step (cycle-accurate timing, scanline-based rendering) --

    fun step() {
        // The MMC3 scanline counter is driven by PPU A12 toggling, which only
        // happens while rendering is enabled (background/sprite pattern fetches).
        // Clocking it with rendering off would advance the IRQ counter during
        // vblank/disabled frames and fire spurious IRQs.
        if (renderEnabled) nes.mapper.step(scanline, cycle)

        if (scanline < 240) {
            if (cycle == 257 && renderEnabled) copyHorizontal()
            if (cycle == 340) renderScanline()
        }

        if (scanline == 241 && cycle == 1) {
            ppuStatus = ppuStatus or 0x80
            frameComplete = true
            if (ppuCtrl and 0x80 != 0) nmiTriggered = true
        }

        if (scanline == 261) {
            if (cycle == 1) ppuStatus = ppuStatus and 0x1F
            if (cycle in 280..304 && renderEnabled) copyVertical()
            if (cycle == 257 && renderEnabled) copyHorizontal()
        }

        cycle++
        if (cycle > 340) {
            cycle = 0
            scanline++
            if (scanline > 261) {
                scanline = 0
                oddFrame = !oddFrame
                if (oddFrame && renderEnabled) cycle = 1
            }
        }
    }

    // -- Scanline rendering --

    private fun renderScanline() {
        if (!renderEnabled) {
            val bgColor = paletteCache[0]
            val offset = scanline * WIDTH
            for (x in 0 until WIDTH) frameBuffer[offset + x] = bgColor
            return
        }

        renderBgScanline()
        renderSpriteScanline()

        // Increment Y at end of visible scanline
        incrementY()
    }

    private fun renderBgScanline() {
        val bgTable = if (ppuCtrl and 0x10 != 0) 0x1000 else 0
        var vAddr = v
        var screenX = 0

        // Render up to 33 tiles (to cover fine scroll offset)
        for (tile in 0 until 34) {
            if (screenX >= WIDTH) break

            val fineY = (vAddr shr 12) and 7
            val ntAddr = 0x2000 or (vAddr and 0x0FFF)
            val tileNum = ppuRead(ntAddr)

            val attrAddr = 0x23C0 or (vAddr and 0x0C00) or
                    ((vAddr shr 4) and 0x38) or ((vAddr shr 2) and 7)
            val attrShift = ((vAddr shr 4) and 4) or (vAddr and 2)
            val palette = (ppuRead(attrAddr) shr attrShift) and 3

            val patAddr = bgTable + tileNum * 16 + fineY
            val lo = ppuRead(patAddr)
            val hi = ppuRead(patAddr + 8)

            val startBit = if (tile == 0) fineX else 0

            for (bit in startBit until 8) {
                if (screenX >= WIDTH) break
                if (showBg && (showBgLeft || screenX >= 8)) {
                    val shift = 7 - bit
                    val pixel = ((lo shr shift) and 1) or (((hi shr shift) and 1) shl 1)
                    bgPixel[screenX] = pixel
                    bgPalette[screenX] = palette
                } else {
                    bgPixel[screenX] = 0
                    bgPalette[screenX] = 0
                }
                screenX++
            }

            // Increment coarseX in vAddr
            if (vAddr and 0x001F == 31) {
                vAddr = (vAddr and 0x7FE0) xor 0x0400
            } else {
                vAddr++
            }
        }
    }

    private fun renderSpriteScanline() {
        val spriteHeight = if (ppuCtrl and 0x20 != 0) 16 else 8
        val offset = scanline * WIDTH

        // Collect up to 8 sprites on this scanline into the reusable buffers.
        var count = 0
        for (i in 0 until 64) {
            if (count >= 8) break
            val y = (oam[i * 4].toInt() and 0xFF) + 1
            val row = scanline - y
            if (row < 0 || row >= spriteHeight) continue

            val tileIndex = oam[i * 4 + 1].toInt() and 0xFF
            val attr = oam[i * 4 + 2].toInt() and 0xFF
            val sx = oam[i * 4 + 3].toInt() and 0xFF
            val flipH = attr and 0x40 != 0
            val flipV = attr and 0x80 != 0

            var tileRow = if (flipV) spriteHeight - 1 - row else row
            val patternAddr: Int
            if (spriteHeight == 8) {
                val sprTable = if (ppuCtrl and 0x08 != 0) 0x1000 else 0
                patternAddr = sprTable + tileIndex * 16 + tileRow
            } else {
                val table = (tileIndex and 1) * 0x1000
                var tile = tileIndex and 0xFE
                if (tileRow >= 8) { tile++; tileRow -= 8 }
                patternAddr = table + tile * 16 + tileRow
            }

            var lo = ppuRead(patternAddr)
            var hi = ppuRead(patternAddr + 8)
            if (flipH) { lo = reverseBits(lo); hi = reverseBits(hi) }

            sprIndex[count] = i
            sprX[count] = sx
            sprAttr[count] = attr
            sprLo[count] = lo
            sprHi[count] = hi
            count++
        }

        // Render pixels: first fill with background, then apply sprites.
        // Colors are read straight from paletteCache (already resolved to ARGB),
        // avoiding ppuRead + Palette.COLORS lookups for every one of the 256 px.
        val universal = paletteCache[0]   // backdrop ($3F00)
        for (x in 0 until WIDTH) {
            val bg = bgPixel[x]
            // bg != 0 -> entry 1..15 (never a mirrored slot); else backdrop.
            var color = if (bg != 0) paletteCache[(bgPalette[x] * 4 + bg) and 0x1F] else universal

            // Find highest-priority opaque sprite at this pixel
            if (showSprites && (showSpritesLeft || x >= 8)) {
                for (s in 0 until count) {
                    val sx = sprX[s]
                    if (x < sx || x >= sx + 8) continue
                    val bit = 7 - (x - sx)
                    val pixel = ((sprLo[s] shr bit) and 1) or (((sprHi[s] shr bit) and 1) shl 1)
                    if (pixel == 0) continue

                    // Sprite 0 hit detection
                    if (sprIndex[s] == 0 && bg != 0 && x < 255) {
                        ppuStatus = ppuStatus or 0x40
                    }

                    val behindBg = sprAttr[s] and 0x20 != 0
                    if (!behindBg || bg == 0) {
                        val sprPalette = (sprAttr[s] and 3) + 4
                        color = paletteCache[(sprPalette * 4 + pixel) and 0x1F]
                    }
                    break // highest priority sprite wins
                }
            }

            frameBuffer[offset + x] = color
        }
    }

    private fun reverseBits(b: Int): Int {
        var v = b and 0xFF
        v = ((v and 0xF0) shr 4) or ((v and 0x0F) shl 4)
        v = ((v and 0xCC) shr 2) or ((v and 0x33) shl 2)
        v = ((v and 0xAA) shr 1) or ((v and 0x55) shl 1)
        return v
    }

    // -- Scrolling helpers --

    private fun incrementY() {
        if (!renderEnabled) return
        if (v and 0x7000 != 0x7000) {
            v += 0x1000
        } else {
            v = v and 0x0FFF
            var coarseY = (v and 0x03E0) shr 5
            when (coarseY) {
                29 -> { coarseY = 0; v = v xor 0x0800 }
                31 -> { coarseY = 0 }
                else -> coarseY++
            }
            v = (v and 0x7C1F) or (coarseY shl 5)
        }
    }

    private fun copyHorizontal() {
        v = (v and 0x7BE0) or (t and 0x041F)
    }

    private fun copyVertical() {
        v = (v and 0x041F) or (t and 0x7BE0)
    }

    fun saveState(out: DataOutputStream) {
        out.write(nametable)
        out.write(paletteRam)
        out.write(oam)
        out.writeInt(ppuCtrl); out.writeInt(ppuMask); out.writeInt(ppuStatus); out.writeInt(oamAddr)
        out.writeInt(v); out.writeInt(t); out.writeInt(fineX)
        out.writeBoolean(writeToggle)
        out.writeInt(scanline); out.writeInt(cycle)
        out.writeBoolean(frameComplete); out.writeBoolean(nmiTriggered)
        out.writeBoolean(oddFrame); out.writeInt(dataBuffer)
    }

    fun loadState(inp: DataInputStream) {
        inp.readFully(nametable)
        inp.readFully(paletteRam)
        inp.readFully(oam)
        ppuCtrl = inp.readInt(); ppuMask = inp.readInt(); ppuStatus = inp.readInt(); oamAddr = inp.readInt()
        v = inp.readInt(); t = inp.readInt(); fineX = inp.readInt()
        writeToggle = inp.readBoolean()
        scanline = inp.readInt(); cycle = inp.readInt()
        frameComplete = inp.readBoolean(); nmiTriggered = inp.readBoolean()
        oddFrame = inp.readBoolean(); dataBuffer = inp.readInt()
        rebuildPaletteCache()
    }
}
