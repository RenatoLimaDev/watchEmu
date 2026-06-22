package com.watchemu.app.ui

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * AGSL fisheye/barrel-distortion shader (Android 13+). The game is rendered
 * normally into a layer; this shader is applied as a RenderEffect over that
 * layer, so the GPU bends the edges inward — no per-pixel CPU work, emulation
 * untouched. `strength` 0 = none; ~0.15 subtle; ~0.35 strong (CRT-ish).
 */
private const val FISHEYE_AGSL = """
uniform shader content;
uniform float2 size;
uniform float strength;

half4 main(float2 coord) {
    float2 uv = coord / size;          // 0..1
    float2 c = uv - 0.5;               // centered, -0.5..0.5
    float r2 = dot(c, c) * 4.0;        // 0 at center, ~1 near edges
    // Barrel map: pull samples outward as r grows, compressing the edges.
    float2 warped = c * (1.0 - strength * r2);
    float2 src = (warped + 0.5) * size;
    return content.eval(src);
}
"""

/** Builds the Compose RenderEffect that applies the fisheye shader to a layer.
 *  Requires API 33+ (RuntimeShader). */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildFisheyeEffect(w: Float, h: Float, strength: Float): androidx.compose.ui.graphics.RenderEffect {
    val shader = RuntimeShader(FISHEYE_AGSL)
    shader.setFloatUniform("size", w, h)
    shader.setFloatUniform("strength", strength)
    val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
    return effect.asComposeRenderEffect()
}

@Composable
fun WatchScreen(
    bitmap: Bitmap?,
    romLoaded: Boolean,
    frameCount: Int = 0,
    // Floating d-pad overlay (left half). Position is in screen pixels.
    dpadActive: Boolean = false,
    dpadCx: Float = 0f,
    dpadCy: Float = 0f,
    dpadKnobX: Float = 0f,
    dpadKnobY: Float = 0f,
    // A/B button overlay (right half).
    buttonAActive: Boolean = false,
    buttonBActive: Boolean = false,
    // Start/Select overlay (top corners).
    buttonStartActive: Boolean = false,
    buttonSelectActive: Boolean = false,
    // Fisheye level: 0 = off (flat, whole picture), 1 = subtle.
    fisheyeLevel: Int = 0,
    // When true, briefly draw all button areas (long-press reveal). Controls are
    // invisible otherwise so the game owns the whole screen.
    showAreas: Boolean = false
) {
    val context = LocalContext.current
    val pressStart2P = remember {
        try {
            // ResourcesCompat.getFont works back to API 16; Resources.getFont is
            // API 26+ and would crash on the Amazfit Stratos (Android 5.1).
            androidx.core.content.res.ResourcesCompat.getFont(
                context,
                context.resources.getIdentifier("press_start_2p", "font", context.packageName)
            ) ?: Typeface.MONOSPACE
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    // Fisheye strength for the current level; 0 disables the shader.
    val fisheyeStrength = when (fisheyeLevel) {
        1 -> 0.15f       // subtle barrel curve
        else -> 0f       // flat (default)
    }
    val shaderSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // Build the RenderEffect once per (size, strength) — applied to the game layer.
    var layerPx by remember { mutableStateOf(Size.Zero) }
    val gameModifier = if (shaderSupported && fisheyeStrength > 0f && layerPx.minDimension > 0f) {
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                renderEffect = buildFisheyeEffect(layerPx.width, layerPx.height, fisheyeStrength)
            }
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: dark face + game image. This Canvas reads `frameCount`, so it
        // is the only thing the runtime repaints every emulated frame (~60fps).
        // When fisheye is on, this layer carries the GPU RenderEffect.
        Canvas(modifier = gameModifier) {
            // Touch frameCount so this layer invalidates each new frame.
            @Suppress("UNUSED_EXPRESSION") frameCount
            val w = size.width
            val h = size.height
            if (layerPx.width != w || layerPx.height != h) layerPx = Size(w, h)
            val diameter = minOf(w, h)
            val ox = (w - diameter) / 2f
            val oy = (h - diameter) / 2f
            // With fisheye on, render the WHOLE picture (fit) and let the shader
            // bend the edges to fill; off = honor the flat fit look.
            drawGameLayer(ox, oy, diameter, bitmap, romLoaded, pressStart2P, fill = false)
        }

        // Layer 2: the d-pad is the ONLY control shown during play — it appears
        // under the finger so the player has a sense of direction. Action buttons
        // stay invisible. A long-press additionally flips `showAreas` on to draw
        // the zone boundaries (pizza slices) as a sharp, clearly visible guide.
        // Lines match radialButtonAt(): bottom half = d-pad; top half is four
        // 45-degree slices SELECT | START | B | A (left -> right).
        if (dpadActive || showAreas) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val diameter = minOf(w, h)

                if (showAreas) {
                    val cx = w * 0.5f
                    val cy = h * 0.5f
                    val reach = diameter

                    // Bright rays from center: middle split + three top dividers.
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

                    // Crisp labels: dark outline + bright fill so they pop over the game.
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

                // The live d-pad under the finger (sense of direction while playing).
                if (dpadActive) {
                    drawFloatingDpad(dpadCx, dpadCy, diameter * 0.11f, dpadKnobX, dpadKnobY)
                }
            }
        }
    }
}

/** Dark face + the emulated game image. Repainted every frame. */
private fun DrawScope.drawGameLayer(
    ox: Float, oy: Float, diameter: Float,
    bitmap: Bitmap?, romLoaded: Boolean,
    font: Typeface, fill: Boolean
) {
    val radius = diameter / 2f
    val centerX = ox + radius
    val centerY = oy + radius

    // --- Console body: the area around the screen looks like the handheld's
    // shell instead of flat black. Vertical graphite gradient (lighter at top)
    // for volume, plus a soft top highlight like light on plastic/metal.
    drawCircle(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF3A3D44), Color(0xFF26282E), Color(0xFF131418)),
            startY = oy, endY = oy + diameter
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)),
            center = Offset(centerX, oy + diameter * 0.12f),
            radius = diameter * 0.55f
        ),
        radius = radius,
        center = Offset(centerX, centerY)
    )

    // Game screen = a rounded-rectangle "console screen" inset into the body, so
    // the shell shows as a frame around it. NES (256:240) ~ box (263:247), so the
    // picture fills the rounded rect with no stretching.
    val gameW = diameter * 0.80f
    val gameH = gameW * (240f / 256f)
    val gameX = ox + (diameter - gameW) / 2f
    val gameY = oy + (diameter - gameH) / 2f
    val cr = gameW * 0.19f                 // corner radius (~50 on a 263-wide box)
    val roundRect = androidx.compose.ui.geometry.RoundRect(
        gameX, gameY, gameX + gameW, gameY + gameH, CornerRadius(cr, cr)
    )
    val roundPath = Path().apply { addRoundRect(roundRect) }

    // Recessed bezel around the screen (dark ring = screen sits inside the body).
    drawRoundRect(
        color = Color(0xFF0A0B0D),
        topLeft = Offset(gameX - diameter * 0.012f, gameY - diameter * 0.012f),
        size = Size(gameW + diameter * 0.024f, gameH + diameter * 0.024f),
        cornerRadius = CornerRadius(cr * 1.1f, cr * 1.1f)
    )

    if (bitmap != null && romLoaded) {
        clipPath(roundPath) {
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = androidx.compose.ui.unit.IntOffset(gameX.toInt(), gameY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(gameW.toInt(), gameH.toInt()),
                filterQuality = FilterQuality.None
            )
        }
    } else {
        clipPath(roundPath) {
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
}

/** Floating d-pad drawn under the finger — the only control visible during play,
 *  giving a clear sense of direction. The knob offset (-1..1) shows the push. */
private fun DrawScope.drawFloatingDpad(
    cx: Float, cy: Float, radius: Float, knobX: Float, knobY: Float
) {
    // Dark backing ring for contrast against bright game scenes.
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

    // Direction hints (4 spokes) — bright and clear.
    val hint = Color(0xCCFFFFFF)
    val hr = radius * 0.78f
    drawLine(hint, Offset(cx, cy - hr * 0.6f), Offset(cx, cy - hr), 4f)
    drawLine(hint, Offset(cx, cy + hr * 0.6f), Offset(cx, cy + hr), 4f)
    drawLine(hint, Offset(cx - hr * 0.6f, cy), Offset(cx - hr, cy), 4f)
    drawLine(hint, Offset(cx + hr * 0.6f, cy), Offset(cx + hr, cy), 4f)

    // Knob — moves toward the pressed direction
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

/** A circular action button (A/B). Faint when idle, bright when pressed. */
private fun DrawScope.drawActionButton(
    cx: Float, cy: Float, radius: Float, label: String, pressed: Boolean, font: Typeface
) {
    val fill = if (pressed) {
        Brush.radialGradient(
            colors = listOf(Color(0xFFF0D060), Color(0xFFD4920A)),
            center = Offset(cx, cy), radius = radius
        )
    } else {
        Brush.radialGradient(
            colors = listOf(Color(0x33F0C040), Color(0x18D4920A)),
            center = Offset(cx, cy), radius = radius
        )
    }
    drawCircle(brush = fill, radius = radius, center = Offset(cx, cy))
    drawCircle(
        color = if (pressed) Color(0xFF6B3A10) else Color(0x66D4920A),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(if (pressed) 3f else 2f)
    )
    val textPaint = android.graphics.Paint().apply {
        color = if (pressed) 0xFF3E2800.toInt() else 0x88FFFFFF.toInt()
        textSize = radius * 0.6f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = font
        isAntiAlias = true
    }
    // Vertically center the glyph on the button.
    val fm = textPaint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(label, cx, baseline, textPaint)
}

/** A small pill in a top corner for the rarely-used Start/Select. Very faint
 *  when idle so it doesn't distract; lights up when pressed. */
private fun DrawScope.drawCornerButton(
    cx: Float, cy: Float, diameter: Float, label: String, pressed: Boolean, font: Typeface
) {
    val pillW = diameter * 0.26f
    val pillH = diameter * 0.085f
    val left = cx - pillW / 2f
    val top = cy - pillH / 2f
    val corner = CornerRadius(pillH / 2f, pillH / 2f)
    drawRoundRect(
        color = if (pressed) Color(0xCCD4920A) else Color(0x22FFFFFF),
        topLeft = Offset(left, top),
        size = Size(pillW, pillH),
        cornerRadius = corner
    )
    drawRoundRect(
        color = if (pressed) Color(0xFF6B3A10) else Color(0x44FFFFFF),
        topLeft = Offset(left, top),
        size = Size(pillW, pillH),
        cornerRadius = corner,
        style = Stroke(if (pressed) 2.5f else 1.5f)
    )
    val textPaint = android.graphics.Paint().apply {
        color = if (pressed) 0xFF3E2800.toInt() else 0x99FFFFFF.toInt()
        textSize = pillH * 0.32f
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = font
        isAntiAlias = true
        letterSpacing = 0.03f
    }
    val fm = textPaint.fontMetrics
    val baseline = cy - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.drawText(label, cx, baseline, textPaint)
}
