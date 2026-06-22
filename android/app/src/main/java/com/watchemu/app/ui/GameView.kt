package com.watchemu.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.view.TextureView
import androidx.core.content.res.ResourcesCompat
import com.watchemu.app.R

/**
 * Renders the NES picture directly on a hardware-accelerated TextureView,
 * driven from the emulation thread. This replaces the previous per-frame
 * Jetpack Compose Canvas recomposition, which was too heavy for the Amazfit
 * Stratos's modest SoC: now each frame is just a cached-background blit plus one
 * scaled bitmap draw, with no Compose work on the 60 fps path.
 *
 * A TextureView (not a SurfaceView) is used so it composites inside the normal
 * view hierarchy — the Compose control overlay drawn on top and the screen
 * transition animation both work without z-ordering glitches.
 */
class GameView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile private var available = false
    @Volatile private var frame: Bitmap? = null

    // Cached console body (graphite shell + bezel + idle screen). Rebuilt only
    // when the view size changes, then blitted every frame.
    private var background: Bitmap? = null
    private var viewW = 0
    private var viewH = 0

    // Destination rect of the game picture inside the shell (pixels).
    private val gameDst = RectF()
    private val frameSrc = Rect()
    private val framePaint = Paint().apply { isFilterBitmap = false } // crisp NES pixels

    private val pressStart2P: Typeface = try {
        ResourcesCompat.getFont(
            context,
            context.resources.getIdentifier("press_start_2p", "font", context.packageName)
        ) ?: Typeface.MONOSPACE
    } catch (_: Exception) {
        Typeface.MONOSPACE
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** Push a freshly emulated frame. Safe to call from the emulation thread. */
    fun submitFrame(bmp: Bitmap) {
        frame = bmp
        render()
    }

    @Synchronized
    private fun render() {
        if (!available) return
        val canvas = try { lockCanvas() } catch (_: Exception) { null } ?: return
        try {
            drawScene(canvas)
        } finally {
            try { unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }
    }

    private fun drawScene(canvas: Canvas) {
        val bg = background
        if (bg != null) canvas.drawBitmap(bg, 0f, 0f, null) else canvas.drawColor(Color.BLACK)
        val f = frame
        if (f != null) {
            frameSrc.set(0, 0, f.width, f.height)
            canvas.drawBitmap(f, frameSrc, gameDst, framePaint)
        }
    }

    /** Build the static shell + idle screen once per size. Mirrors the geometry
     *  the Compose version used (80% of the min dimension, NES 256:240). */
    private fun buildBackground(w: Int, h: Int) {
        viewW = w
        viewH = h
        background?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)

        val diameter = minOf(w, h).toFloat()
        val ox = (w - diameter) / 2f
        val oy = (h - diameter) / 2f
        val radius = diameter / 2f
        val centerX = ox + radius
        val centerY = oy + radius

        // Console body: vertical graphite gradient circle.
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, oy, 0f, oy + diameter,
                intArrayOf(0xFF3A3D44.toInt(), 0xFF26282E.toInt(), 0xFF131418.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(centerX, centerY, radius, bodyPaint)

        // Soft top highlight.
        val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                centerX, oy + diameter * 0.12f, diameter * 0.55f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(centerX, centerY, radius, hiPaint)

        // Game screen rect.
        val gameW = diameter * 0.80f
        val gameH = gameW * (240f / 256f)
        val gameX = ox + (diameter - gameW) / 2f
        val gameY = oy + (diameter - gameH) / 2f
        val cr = gameW * 0.19f
        gameDst.set(gameX, gameY, gameX + gameW, gameY + gameH)

        // Recessed bezel around the screen.
        val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0B0D.toInt() }
        val bezelInset = diameter * 0.012f
        c.drawRoundRect(
            RectF(gameX - bezelInset, gameY - bezelInset, gameX + gameW + bezelInset, gameY + gameH + bezelInset),
            cr * 1.1f, cr * 1.1f, bezelPaint
        )

        // Idle screen (Game Boy green) shown until the first frame arrives.
        val clip = Path().apply { addRoundRect(gameDst, cr, cr, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawColor(0xFF8BAC0F.toInt())
        val grid = Paint().apply { color = 0x1E304800; strokeWidth = 1f }
        val stepX = gameW / 20f
        for (i in 0..20) c.drawLine(gameX + i * stepX, gameY, gameX + i * stepX, gameY + gameH, grid)
        val stepY = gameH / 18f
        for (j in 0..18) c.drawLine(gameX, gameY + j * stepY, gameX + gameW, gameY + j * stepY, grid)
        c.restore()

        background = bmp
    }

    // --- SurfaceTextureListener ---
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
        buildBackground(w, h)
        available = true
        render()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
        buildBackground(w, h)
        render()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        available = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
}
