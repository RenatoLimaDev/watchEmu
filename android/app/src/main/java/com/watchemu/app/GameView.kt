package com.watchemu.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.watchemu.app.nes.Controller
import com.watchemu.app.nes.Nes
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classic Android View that renders the watch face + NES frame and handles
 * touch input. Replaces the former Jetpack Compose UI so the app runs on old
 * (Android 4.4/5.1) standalone watches. Software-drawn, solid colors, no
 * gradients — light enough for weak chips.
 *
 * Frames arrive from the audio thread via [submitFrame]; they are uploaded to
 * the bitmap on the UI thread inside [onDraw] (under a lock) so the GPU never
 * reads a half-written buffer.
 */
class GameView(context: Context) : View(context) {

    interface Input {
        fun down(button: Int)
        fun up(button: Int)
    }
    var input: Input? = null

    private val frame = Bitmap.createBitmap(Nes.WIDTH, Nes.HEIGHT, Bitmap.Config.ARGB_8888)
    private val pendingPixels = IntArray(Nes.WIDTH * Nes.HEIGHT)
    private val pixelLock = Any()
    private var hasPending = false

    private val bmpPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0C0D10.toInt() }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD4920A.toInt() }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0A820.toInt() }
    private val btnActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0D060.toInt() }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC8C0B0.toInt() }
    private val knobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD4A820.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A3000.toInt()
        textAlign = Paint.Align.CENTER
        typeface = try {
            Typeface.createFromAsset(context.assets, "press_start_2p.ttf")
        } catch (_: Exception) {
            Typeface.MONOSPACE
        }
    }

    private val srcRect = Rect(0, 0, Nes.WIDTH, Nes.HEIGHT)
    private val dstRect = Rect()

    // Analog stick + button pointer tracking (ported from the old touch handler).
    private var analogX = 0f
    private var analogY = 0f
    private var analogPointerId = -1
    private var analogDirX = 0
    private var analogDirY = 0
    private val activeButtons = HashMap<Int, Int>()

    init {
        isClickable = true
        isFocusable = true
    }

    /** Called from the audio thread when a new frame is ready. */
    fun submitFrame(src: IntArray) {
        synchronized(pixelLock) {
            System.arraycopy(src, 0, pendingPixels, 0, pendingPixels.size)
            hasPending = true
        }
        postInvalidate()
    }

    // ---- Geometry (shared by drawing and hit-testing) ----
    private var ox = 0f; private var oy = 0f; private var dia = 0f

    private fun recomputeGeometry() {
        val w = width.toFloat(); val h = height.toFloat()
        dia = minOf(w, h)
        ox = (w - dia) / 2f
        oy = (h - dia) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(pixelLock) {
            if (hasPending) {
                frame.setPixels(pendingPixels, 0, Nes.WIDTH, 0, 0, Nes.WIDTH, Nes.HEIGHT)
                hasPending = false
            }
        }
        recomputeGeometry()
        val d = dia
        val cx = ox + d / 2f
        val cy = oy + d / 2f

        canvas.drawColor(0xFF000000.toInt())
        canvas.drawCircle(cx, cy, d / 2f, facePaint)

        // Game screen (nearest-neighbor scaled)
        val gw = d * 0.84f
        val gh = gw * (Nes.HEIGHT.toFloat() / Nes.WIDTH.toFloat())
        val gx = ox + (d - gw) / 2f
        val gy = oy + (d - gh) / 2f - d * 0.01f
        dstRect.set(gx.toInt(), gy.toInt(), (gx + gw).toInt(), (gy + gh).toInt())
        canvas.drawBitmap(frame, srcRect, dstRect, bmpPaint)

        // Bottom golden tray
        canvas.save()
        canvas.clipRect(ox, oy + d * 0.82f, ox + d, oy + d)
        canvas.drawCircle(cx, cy, d / 2f, bezelPaint)
        canvas.restore()

        // Face / shoulder buttons (rects aligned with the hit regions below)
        labelPaint.textSize = d * 0.05f
        drawLabeledRect(canvas, ox + d * 0.08f, oy, ox + d * 0.50f, oy + d * 0.15f, "A", isDown(Controller.BUTTON_A))
        drawLabeledRect(canvas, ox + d * 0.50f, oy, ox + d * 0.92f, oy + d * 0.15f, "B", isDown(Controller.BUTTON_B))
        labelPaint.textSize = d * 0.035f
        drawLabeledRect(canvas, ox, oy + d * 0.10f, ox + d * 0.13f, oy + d * 0.90f, "SEL", isDown(Controller.BUTTON_SELECT))
        drawLabeledRect(canvas, ox + d * 0.87f, oy + d * 0.10f, ox + d, oy + d * 0.90f, "ST", isDown(Controller.BUTTON_START))

        // Analog stick
        val stickCx = cx
        val stickCy = oy + d * 0.91f
        val baseR = d * 0.09f
        knobPaint.color = 0xFF8A8270.toInt()
        canvas.drawCircle(stickCx, stickCy, baseR, knobPaint)
        val maxMove = baseR * 0.4f
        val active = analogX != 0f || analogY != 0f
        canvas.drawCircle(
            stickCx + analogX * maxMove, stickCy + analogY * maxMove, baseR * 0.6f,
            if (active) knobActivePaint else knobPaint.also { it.color = 0xFFC8C0B0.toInt() }
        )
    }

    private fun isDown(button: Int): Boolean = activeButtons.containsValue(button)

    private fun drawLabeledRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, down: Boolean) {
        canvas.save()
        canvas.clipRect(ox, oy, ox + dia, oy + dia)
        canvas.drawRect(l, t, r, b, if (down) btnActivePaint else btnPaint)
        canvas.restore()
        val tx = (l + r) / 2f
        val ty = (t + b) / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, tx, ty, labelPaint)
    }

    // ---- Touch ----
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val d = dia
        if (d <= 0f) return true
        val dpadCx = ox + d * 0.5f
        val dpadCy = oy + d * 0.91f
        val dpadR = d * 0.09f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val ex = event.getX(idx); val ey = event.getY(idx)
                val adx = ex - dpadCx; val ady = ey - dpadCy
                if (sqrt(adx * adx + ady * ady) <= dpadR * 1.3f) {
                    analogPointerId = id
                    updateAnalog(ex - dpadCx, ey - dpadCy, dpadR)
                } else {
                    val btn = hitTest((ex - ox) / d, (ey - oy) / d)
                    if (btn >= 0) { activeButtons[id] = btn; input?.down(btn) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val ex = event.getX(i); val ey = event.getY(i)
                    if (id == analogPointerId) {
                        updateAnalog(ex - dpadCx, ey - dpadCy, dpadR)
                    } else {
                        val btn = hitTest((ex - ox) / d, (ey - oy) / d)
                        val prev = activeButtons[id]
                        if (prev != null && prev != btn) {
                            input?.up(prev)
                            if (btn >= 0) { activeButtons[id] = btn; input?.down(btn) }
                            else activeButtons.remove(id)
                        } else if (prev == null && btn >= 0) {
                            activeButtons[id] = btn; input?.down(btn)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == analogPointerId) releaseAnalog()
                else activeButtons.remove(id)?.let { input?.up(it) }
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAnalog()
                for (b in activeButtons.values) input?.up(b)
                activeButtons.clear()
            }
        }
        invalidate()
        return true
    }

    private fun hitTest(px: Float, py: Float): Int {
        val cx = px - 0.5f; val cy = py - 0.5f
        if (sqrt(cx * cx + cy * cy) > 0.5f) return -1
        if (py < 0.15f && px > 0.08f && px < 0.50f) return Controller.BUTTON_A
        if (py < 0.15f && px > 0.50f && px < 0.92f) return Controller.BUTTON_B
        if (px < 0.13f && py > 0.10f && py < 0.90f) return Controller.BUTTON_SELECT
        if (px > 0.87f && py > 0.10f && py < 0.90f) return Controller.BUTTON_START
        return -1
    }

    private fun updateAnalog(rawDx: Float, rawDy: Float, radius: Float) {
        var dx = rawDx; var dy = rawDy
        val dist = sqrt(dx * dx + dy * dy)
        val maxDist = radius * 0.8f
        if (dist > maxDist) { dx = dx / dist * maxDist; dy = dy / dist * maxDist }
        analogX = (dx / maxDist).coerceIn(-1f, 1f)
        analogY = (dy / maxDist).coerceIn(-1f, 1f)

        val deadzone = 0.25f
        val norm = (dist / maxDist).coerceAtMost(1f)
        val newDirX: Int; val newDirY: Int
        if (norm > deadzone) {
            val angle = atan2(dy, dx)
            val cosA = cos(angle); val sinA = sin(angle)
            newDirX = if (cosA > 0.4f) 1 else if (cosA < -0.4f) -1 else 0
            newDirY = if (sinA > 0.4f) 1 else if (sinA < -0.4f) -1 else 0
        } else { newDirX = 0; newDirY = 0 }

        if (newDirX != analogDirX) {
            if (analogDirX == -1) input?.up(Controller.BUTTON_LEFT)
            if (analogDirX == 1) input?.up(Controller.BUTTON_RIGHT)
            analogDirX = newDirX
            if (analogDirX == -1) input?.down(Controller.BUTTON_LEFT)
            if (analogDirX == 1) input?.down(Controller.BUTTON_RIGHT)
        }
        if (newDirY != analogDirY) {
            if (analogDirY == -1) input?.up(Controller.BUTTON_UP)
            if (analogDirY == 1) input?.up(Controller.BUTTON_DOWN)
            analogDirY = newDirY
            if (analogDirY == -1) input?.down(Controller.BUTTON_UP)
            if (analogDirY == 1) input?.down(Controller.BUTTON_DOWN)
        }
    }

    private fun releaseAnalog() {
        analogPointerId = -1
        analogX = 0f; analogY = 0f
        if (analogDirX == -1) input?.up(Controller.BUTTON_LEFT)
        if (analogDirX == 1) input?.up(Controller.BUTTON_RIGHT)
        if (analogDirY == -1) input?.up(Controller.BUTTON_UP)
        if (analogDirY == 1) input?.up(Controller.BUTTON_DOWN)
        analogDirX = 0; analogDirY = 0
    }
}
