package com.watchemu.app.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext

@Composable
fun WatchScreen(
    bitmap: Bitmap?,
    romLoaded: Boolean,
    frameCount: Int = 0,
    bezelColor: Color = Color(0xFFD4920A),
    bezelAccent: Color = Color(0xFF6B3A10),
    stickOffsetX: Float = 0f,
    stickOffsetY: Float = 0f
) {
    val context = LocalContext.current
    val pressStart2P = remember {
        try {
            context.resources.getFont(
                context.resources.getIdentifier("press_start_2p", "font", context.packageName)
            )
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val diameter = minOf(w, h)
            val ox = (w - diameter) / 2f
            val oy = (h - diameter) / 2f
            drawWatch(ox, oy, diameter, bitmap, romLoaded, bezelColor, bezelAccent, stickOffsetX, stickOffsetY, pressStart2P)
        }
    }
}

private fun DrawScope.drawWatch(
    ox: Float, oy: Float, diameter: Float,
    bitmap: Bitmap?, romLoaded: Boolean,
    bezelColor: Color, bezelAccent: Color,
    stickOffsetX: Float, stickOffsetY: Float,
    font: Typeface
) {
    val radius = diameter / 2f
    val centerX = ox + radius
    val centerY = oy + radius

    // Dark watch face background
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFF0C0D10), Color(0xFF050506)),
            center = Offset(centerX, centerY - diameter * 0.12f),
            radius = radius
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )

    // Game screen area
    val gameW = diameter * 0.84f
    val gameH = gameW * (240f / 256f)
    val gameX = ox + (diameter - gameW) / 2f
    val gameY = oy + (diameter - gameH) / 2f - diameter * 0.01f

    if (bitmap != null && romLoaded) {
        clipRect(gameX, gameY, gameX + gameW, gameY + gameH) {
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(gameX.toInt(), gameY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(gameW.toInt(), gameH.toInt()),
                filterQuality = FilterQuality.None
            )
        }
    } else {
        clipRect(gameX, gameY, gameX + gameW, gameY + gameH) {
            drawRect(Color(0xFF8BAC0F), Offset(gameX, gameY), Size(gameW, gameH))
            val gridColor = Color(0x1E304800)
            val stepX = gameW / 20f
            for (i in 0..20) {
                val x = gameX + i * stepX
                drawLine(gridColor, Offset(x, gameY), Offset(x, gameY + gameH), 1f)
            }
            val stepY = gameH / 18f
            for (j in 0..18) {
                val y = gameY + j * stepY
                drawLine(gridColor, Offset(gameX, y), Offset(gameX + gameW, y), 1f)
            }
        }
        val textPaint = android.graphics.Paint().apply {
            color = 0xFF444A54.toInt()
            textSize = diameter * 0.04f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = font
        }
        drawContext.canvas.nativeCanvas.drawText("Loading...", centerX, centerY, textPaint)
    }

    // Bottom tray (golden crescent at bottom)
    val trayTop = oy + diameter * 0.82f
    clipRect(ox, trayTop, ox + diameter, oy + diameter) {
        drawCircle(bezelColor, radius, Offset(centerX, centerY))
    }
    drawArc(
        color = bezelAccent,
        startAngle = 35f, sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(ox + 2, oy + 2),
        size = Size(diameter - 4, diameter - 4),
        style = Stroke(2f)
    )

    // Buttons with Press Start 2P font
    drawCrescentButton(ox, oy, diameter, isLeft = true, label = "A", bezelColor, bezelAccent, font)
    drawCrescentButton(ox, oy, diameter, isLeft = false, label = "B", bezelColor, bezelAccent, font)
    drawSideButton(ox, oy, diameter, isLeft = true, label = "SELECT", bezelColor, bezelAccent, font)
    drawSideButton(ox, oy, diameter, isLeft = false, label = "START", bezelColor, bezelAccent, font)

    drawAnalogStick(centerX, oy + diameter * 0.91f, diameter * 0.09f, stickOffsetX, stickOffsetY)
}

private fun DrawScope.drawCrescentButton(
    ox: Float, oy: Float, diameter: Float, isLeft: Boolean, label: String,
    bezelColor: Color, bezelAccent: Color, font: Typeface
) {
    val btnW = diameter * 0.40f
    val btnH = diameter * 0.125f
    val btnX = if (isLeft) ox + diameter * 0.10f else ox + diameter * 0.50f
    val btnY = oy

    clipRect(ox, oy, ox + diameter, oy + diameter) {
        // Button background with gradient (matching CSS linear-gradient)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF0C040), Color(0xFFD4920A)),
                startY = btnY, endY = btnY + btnH
            ),
            topLeft = Offset(btnX, btnY),
            size = Size(btnW, btnH)
        )
        // Bottom border
        drawLine(bezelAccent, Offset(btnX, btnY + btnH), Offset(btnX + btnW, btnY + btnH), 2f)
        // Inner divider between A and B
        if (isLeft) {
            drawLine(bezelAccent, Offset(btnX + btnW, btnY), Offset(btnX + btnW, btnY + btnH), 1f)
        } else {
            drawLine(bezelAccent, Offset(btnX, btnY), Offset(btnX, btnY + btnH), 1f)
        }
        // Highlight at top for 3D effect
        drawLine(
            Color(0x4DFFFFFF),
            Offset(btnX + 2, btnY + 1),
            Offset(btnX + btnW - 2, btnY + 1),
            1f
        )
    }

    val textPaint = android.graphics.Paint().apply {
        color = 0xFF5A3000.toInt()
        textSize = diameter * 0.035f
        textAlign = if (isLeft) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
        typeface = font
        isAntiAlias = true
    }
    val tx = if (isLeft) btnX + btnW - diameter * 0.06f else btnX + diameter * 0.06f
    drawContext.canvas.nativeCanvas.drawText(label, tx, btnY + btnH * 0.72f, textPaint)
}

private fun DrawScope.drawSideButton(
    ox: Float, oy: Float, diameter: Float, isLeft: Boolean, label: String,
    bezelColor: Color, bezelAccent: Color, font: Typeface
) {
    val btnW = diameter * 0.105f
    val btnH = diameter * 0.80f
    val btnX = if (isLeft) ox else ox + diameter - btnW
    val btnY = oy + diameter * 0.10f

    clipRect(ox, oy, ox + diameter, oy + diameter) {
        // Button background with gradient
        drawRect(
            brush = if (isLeft) {
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFFD4920A), Color(0xFFF0C040)),
                    startX = btnX, endX = btnX + btnW
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(Color(0xFFF0C040), Color(0xFFD4920A)),
                    startX = btnX, endX = btnX + btnW
                )
            },
            topLeft = Offset(btnX, btnY),
            size = Size(btnW, btnH)
        )
        // Inner border (the edge facing the screen)
        val borderX = if (isLeft) btnX + btnW else btnX
        drawLine(bezelAccent, Offset(borderX, btnY), Offset(borderX, btnY + btnH), 2f)
        // Outer border
        val outerBorderX = if (isLeft) btnX else btnX + btnW
        drawLine(bezelAccent, Offset(outerBorderX, btnY), Offset(outerBorderX, btnY + btnH), 2f)
    }

    val textPaint = android.graphics.Paint().apply {
        color = 0xFF5A3000.toInt()
        textSize = diameter * 0.032f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = font
        isAntiAlias = true
        letterSpacing = 0.05f
        isFakeBoldText = true
        strokeWidth = diameter * 0.002f
        style = android.graphics.Paint.Style.FILL_AND_STROKE
    }
    val tx = btnX + btnW * 0.45f
    val ty = oy + diameter / 2f
    drawContext.canvas.nativeCanvas.save()
    drawContext.canvas.nativeCanvas.rotate(90f, tx, ty)
    drawContext.canvas.nativeCanvas.drawText(label, tx, ty + diameter * 0.01f, textPaint)
    drawContext.canvas.nativeCanvas.restore()
}

private fun DrawScope.drawAnalogStick(cx: Float, cy: Float, radius: Float, offsetX: Float, offsetY: Float) {
    // Base with shadow
    drawCircle(
        color = Color(0x33000000),
        radius = radius + 2f,
        center = Offset(cx, cy + 2f)
    )
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFC8C0B0), Color(0xFFA89880)),
            startY = cy - radius, endY = cy + radius
        ),
        radius = radius, center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFF6B3A10), radius = radius,
        center = Offset(cx, cy),
        style = Stroke(2f)
    )
    // Inner shadow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x33000000)),
            center = Offset(cx, cy),
            radius = radius
        ),
        radius = radius,
        center = Offset(cx, cy)
    )

    // Stick knob — moves with finger
    val stickR = radius * 0.6f
    val maxMove = radius * 0.4f
    val stickCx = cx + offsetX * maxMove
    val stickCy = cy + offsetY * maxMove

    val isActive = offsetX != 0f || offsetY != 0f

    // Knob shadow
    drawCircle(
        color = Color(0x4D000000),
        radius = stickR + 1f,
        center = Offset(stickCx, stickCy + 3f)
    )

    drawCircle(
        brush = if (isActive) {
            Brush.linearGradient(
                colors = listOf(Color(0xFFF0D870), Color(0xFFD4A820)),
                start = Offset(stickCx - stickR, stickCy - stickR),
                end = Offset(stickCx + stickR, stickCy + stickR)
            )
        } else {
            Brush.linearGradient(
                colors = listOf(Color(0xFFE8E0D4), Color(0xFFC8C0B0)),
                start = Offset(stickCx - stickR, stickCy - stickR),
                end = Offset(stickCx + stickR, stickCy + stickR)
            )
        },
        radius = stickR, center = Offset(stickCx, stickCy)
    )
    drawCircle(
        color = if (isActive) Color(0xFF6B3A10) else Color(0xFFA89880),
        radius = stickR,
        center = Offset(stickCx, stickCy),
        style = Stroke(2f)
    )
    // Highlight on knob for 3D effect
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x40FFFFFF), Color.Transparent),
            center = Offset(stickCx - stickR * 0.3f, stickCy - stickR * 0.3f),
            radius = stickR * 0.8f
        ),
        radius = stickR * 0.7f,
        center = Offset(stickCx - stickR * 0.2f, stickCy - stickR * 0.2f)
    )

    // Arrow hints (only show when not active)
    if (!isActive) {
        val arrowPaint = android.graphics.Paint().apply {
            color = 0x40000000
            textSize = radius * 0.5f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val nc = drawContext.canvas.nativeCanvas
        nc.drawText("▲", cx, cy - radius * 0.25f, arrowPaint)
        nc.drawText("▼", cx, cy + radius * 0.55f, arrowPaint)
        nc.drawText("◀", cx - radius * 0.45f, cy + radius * 0.15f, arrowPaint)
        nc.drawText("▶", cx + radius * 0.45f, cy + radius * 0.15f, arrowPaint)
    }
}
