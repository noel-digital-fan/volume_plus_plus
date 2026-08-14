package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * The eyedropper's picking surface: a frozen screenshot the user drags a crosshair over, with a
 * magnifier loupe riding above their finger so the exact pixel under it is visible rather than
 * hidden beneath their fingertip.
 *
 * Drawn entirely on a [Canvas] with no external libraries, like [ColorWheelPicker], because it lives
 * in a WindowManager overlay rather than in the Compose tree.
 *
 * The frame is drawn through a fit-centre [Matrix] rather than assumed to match the view 1:1, and
 * touches are mapped back through its inverse. That keeps the sampled pixel correct even when the
 * captured frame and the window disagree about size — which they do whenever the device is rotated
 * mid-pick, or the capture came back at a different resolution from the window's own.
 */
@SuppressLint("ViewConstructor")
class MagnifierLoupeView(
    context: Context,
    private val frame: Bitmap,
    private val onColorChanged: (Int) -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** Maps the frozen frame into this view, and its inverse maps a touch back onto a frame pixel. */
    private val frameMatrix = Matrix()
    private val inverse = Matrix()

    /** The sampled point, in *frame* pixels — the coordinate space that survives a resize. */
    private var sampleX = frame.width / 2f
    private var sampleY = frame.height / 2f

    /** The sampled colour, always opaque: a screen capture carries no meaningful alpha, and the
     *  editor's own alpha slider stays the way to make a colour see-through. */
    var color: Int = Color.BLACK
        private set

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    // Nearest-neighbour on purpose: magnified pixels must stay crisp squares, not a smooth blur.
    private val zoomPaint = Paint().apply { isFilterBitmap = false }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.argb(30, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val loupeRadius get() = dp(56f)
    /** How far above the finger the loupe floats, so the hand never covers what's being inspected. */
    private val loupeLift get() = dp(84f)
    /** Frame pixels across the loupe. Odd, so there is a true centre pixel to outline. */
    private val zoomPixels = 13

    private val src = Rect()
    private val dst = RectF()
    private val clip = Path()

    init {
        isFocusable = false
        sample()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frameMatrix.setRectToRect(
            RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()),
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            Matrix.ScaleToFit.CENTER,
        )
        frameMatrix.invert(inverse)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(frame, frameMatrix, bitmapPaint)

        // Where the sampled frame pixel lands on screen.
        val point = floatArrayOf(sampleX + 0.5f, sampleY + 0.5f)
        frameMatrix.mapPoints(point)
        val px = point[0]
        val py = point[1]

        drawCrosshair(canvas, px, py)
        drawLoupe(canvas, px, py)
    }

    /** A white hairline with a black companion, so it reads against a background of any colour. */
    private fun drawCrosshair(canvas: Canvas, px: Float, py: Float) {
        strokePaint.strokeWidth = dp(3f)
        strokePaint.color = Color.argb(90, 0, 0, 0)
        canvas.drawLine(0f, py, width.toFloat(), py, strokePaint)
        canvas.drawLine(px, 0f, px, height.toFloat(), strokePaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(180, 255, 255, 255)
        canvas.drawLine(0f, py, width.toFloat(), py, strokePaint)
        canvas.drawLine(px, 0f, px, height.toFloat(), strokePaint)
    }

    private fun drawLoupe(canvas: Canvas, px: Float, py: Float) {
        val r = loupeRadius
        // Park it above the finger, flipping below when there's no room, and keep it on screen.
        val cy = if (py - loupeLift - r > 0) py - loupeLift else py + loupeLift
        val cx = px.coerceIn(r + dp(8f), width - r - dp(8f))

        val half = zoomPixels / 2
        src.set(
            sampleX.roundToInt() - half,
            sampleY.roundToInt() - half,
            sampleX.roundToInt() + half + 1,
            sampleY.roundToInt() + half + 1,
        )
        dst.set(cx - r, cy - r, cx + r, cy + r)

        canvas.save()
        clip.reset()
        clip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.clipPath(clip)
        // A neutral ground first: near an edge the source rect runs off the frame, and drawBitmap
        // simply skips what isn't there, which would otherwise show whatever was drawn beneath.
        fillPaint.color = Color.argb(255, 26, 26, 30)
        canvas.drawRect(dst, fillPaint)
        canvas.drawBitmap(frame, src, dst, zoomPaint)

        // A faint grid, one cell per magnified pixel, so individual pixels are countable.
        val cell = (r * 2f) / zoomPixels
        var i = 1
        while (i < zoomPixels) {
            val o = i * cell
            canvas.drawLine(dst.left + o, dst.top, dst.left + o, dst.bottom, gridPaint)
            canvas.drawLine(dst.left, dst.top + o, dst.right, dst.top + o, gridPaint)
            i++
        }
        canvas.restore()

        // The centre pixel — the one actually being picked — ringed so there's no ambiguity.
        val c = cell / 2f
        val centre = RectF(cx - c, cy - c, cx + c, cy + c)
        strokePaint.strokeWidth = dp(2f)
        strokePaint.color = Color.BLACK
        canvas.drawRect(centre, strokePaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.WHITE
        canvas.drawRect(centre, strokePaint)

        // Rim: the live colour between a white and a black ring, so it reads on any wallpaper.
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(120, 0, 0, 0)
        canvas.drawCircle(cx, cy, r + dp(3f), strokePaint)
        strokePaint.strokeWidth = dp(4f)
        strokePaint.color = color
        canvas.drawCircle(cx, cy, r + dp(2f), strokePaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r, strokePaint)

        drawReadout(canvas, cx, cy + r + dp(20f))
    }

    /** The hex value on a chip filled with the colour itself, its text flipped to whichever of black
     *  or white stays legible against it. */
    private fun drawReadout(canvas: Canvas, cx: Float, cy: Float) {
        val label = String.format("#%06X", color and 0x00FFFFFF)
        val w = textPaint.measureText(label) + dp(20f)
        val h = dp(26f)
        val chip = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        fillPaint.color = color
        canvas.drawRoundRect(chip, h / 2f, h / 2f, fillPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(chip, h / 2f, h / 2f, strokePaint)
        textPaint.color = if (isLight(color)) Color.BLACK else Color.WHITE
        canvas.drawText(label, cx, cy + textPaint.textSize / 3f, textPaint)
    }

    /** Rec. 709 relative luminance — the standard test for whether black or white text will read. */
    private fun isLight(argb: Int): Boolean {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val l = 0.2126 * channel(Color.red(argb)) +
            0.7152 * channel(Color.green(argb)) +
            0.0722 * channel(Color.blue(argb))
        return l > 0.5
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val point = floatArrayOf(event.x, event.y)
                inverse.mapPoints(point)
                sampleX = point[0].coerceIn(0f, (frame.width - 1).toFloat())
                sampleY = point[1].coerceIn(0f, (frame.height - 1).toFloat())
                sample()
                invalidate()
            }
            else -> return true
        }
        return true
    }

    private fun sample() {
        val x = sampleX.roundToInt().coerceIn(0, frame.width - 1)
        val y = sampleY.roundToInt().coerceIn(0, frame.height - 1)
        color = frame.getPixel(x, y) or 0xFF000000.toInt()
        onColorChanged(color)
    }
}
