package com.watchemu.app.ui

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The in-game screen. The NES picture is rendered by [GameView] (a TextureView
 * driven from the emulation thread); this composable only hosts that view and
 * paints the transient control overlay (the floating d-pad under the finger and
 * the long-press "reveal areas" guide). Nothing here repaints every frame, so
 * the 60 fps path stays off the Compose thread — important on the Stratos.
 */
@Composable
fun WatchScreen(
    onSurfaceCreated: (GameView) -> Unit,
    // Floating d-pad overlay (bottom half). Position is in screen pixels.
    dpadActive: Boolean = false,
    dpadCx: Float = 0f,
    dpadCy: Float = 0f,
    dpadKnobX: Float = 0f,
    dpadKnobY: Float = 0f,
    // When true, briefly draw all button areas (long-press reveal).
    showAreas: Boolean = false
) {
    val context = LocalContext.current
    val pressStart2P = remember {
        try {
            androidx.core.content.res.ResourcesCompat.getFont(
                context,
                context.resources.getIdentifier("press_start_2p", "font", context.packageName)
            ) ?: Typeface.MONOSPACE
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: the game picture, rendered by the TextureView.
        AndroidView(
            factory = { ctx -> GameView(ctx).also(onSurfaceCreated) },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: transient controls. Only present while a finger is down on the
        // d-pad, or briefly after a long-press reveals the zone boundaries.
        if (dpadActive || showAreas) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val diameter = minOf(w, h)

                if (showAreas) {
                    val cx = w * 0.5f
                    val cy = h * 0.5f
                    val reach = diameter

                    fun ray(deg: Double) {
                        val a = Math.toRadians(deg)
                        drawLine(
                            Color(0xFFFFFFFF),
                            Offset(cx, cy),
                            Offset(cx + (kotlin.math.cos(a) * reach).toFloat(),
                                   cy + (kotlin.math.sin(a) * reach).toFloat()),
                            strokeWidth = 4f
                        )
                    }
                    ray(0.0); ray(180.0)
                    ray(-135.0); ray(-90.0); ray(-45.0)

                    fun label(text: String, deg: Double, rFrac: Float) {
                        val a = Math.toRadians(deg)
                        val r = reach * rFrac
                        val tx = cx + (kotlin.math.cos(a) * r).toFloat()
                        val ty = cy + (kotlin.math.sin(a) * r).toFloat()
                        val base = android.graphics.Paint().apply {
                            textSize = diameter * 0.055f
                            textAlign = android.graphics.Paint.Align.CENTER
                            typeface = pressStart2P
                            isAntiAlias = true
                        }
                        val fm = base.fontMetrics
                        val by = ty - (fm.ascent + fm.descent) / 2f
                        val nc = drawContext.canvas.nativeCanvas
                        val outline = android.graphics.Paint(base).apply {
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = diameter * 0.012f
                            color = 0xFF000000.toInt()
                        }
                        nc.drawText(text, tx, by, outline)
                        base.color = 0xFFFFD24A.toInt()
                        nc.drawText(text, tx, by, base)
                    }
                    label("SEL", -157.5, 0.30f)
                    label("STA", -112.5, 0.34f)
                    label("B",    -67.5, 0.34f)
                    label("A",    -22.5, 0.30f)
                    label("DPAD",  90.0, 0.32f)
                }

                if (dpadActive) {
                    drawFloatingDpad(dpadCx, dpadCy, diameter * 0.13f, dpadKnobX, dpadKnobY)
                }
            }
        }
    }
}

/** Floating d-pad drawn under the finger — the only control visible during play,
 *  giving a clear sense of direction. The knob offset (-1..1) shows the push. */
private fun DrawScope.drawFloatingDpad(
    cx: Float, cy: Float, radius: Float, knobX: Float, knobY: Float
) {
    drawCircle(
        color = Color(0x99000000),
        radius = radius * 1.08f,
        center = Offset(cx, cy)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xCCF0C040), Color(0x66F0C040)),
            center = Offset(cx, cy),
            radius = radius
        ),
        radius = radius,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color(0xFFD4920A),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(3f)
    )

    val hint = Color(0xCCFFFFFF)
    val hr = radius * 0.78f
    drawLine(hint, Offset(cx, cy - hr * 0.6f), Offset(cx, cy - hr), 4f)
    drawLine(hint, Offset(cx, cy + hr * 0.6f), Offset(cx, cy + hr), 4f)
    drawLine(hint, Offset(cx - hr * 0.6f, cy), Offset(cx - hr, cy), 4f)
    drawLine(hint, Offset(cx + hr * 0.6f, cy), Offset(cx + hr, cy), 4f)

    val maxMove = radius * 0.45f
    val knobR = radius * 0.5f
    val kx = cx + knobX * maxMove
    val ky = cy + knobY * maxMove
    drawCircle(color = Color(0x55000000), radius = knobR + 1f, center = Offset(kx, ky + 2f))
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFF0D870), Color(0xFFD4A820)),
            start = Offset(kx - knobR, ky - knobR),
            end = Offset(kx + knobR, ky + knobR)
        ),
        radius = knobR, center = Offset(kx, ky)
    )
    drawCircle(color = Color(0xFF6B3A10), radius = knobR, center = Offset(kx, ky), style = Stroke(2f))
}
