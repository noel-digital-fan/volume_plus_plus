package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A self-contained, modern HSV colour picker drawn entirely on a [Canvas] (no external libraries, so
 * it works inside the WindowManager overlay the live editor uses). It is one composite control:
 *
 * - a **hue + saturation wheel** — hue around the rim, saturation from the centre (0) to the edge (1);
 * - a **value (brightness)** slider below it;
 * - an **alpha (opacity)** slider below that.
 *
 * The three together give full HSVA control; [onColorChanged] fires with the resulting ARGB int as the
 * user drags any of them. [setColor] drives the control the other way (e.g. from the hex field or a
 * swatch selection) without re-emitting. The wheel's knob, both slider thumbs and the live value stay
 * in sync at all times, so the picker and any external hex field always agree.
 */
@SuppressLint("ViewConstructor")
class ColorWheelPicker(
    context: Context,
    private val dark: Boolean,
    private val onColorChanged: (Int) -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // HSVA state (h in 0..360, s/v/a in 0..1). The single source of truth for what's shown.
    private val hsv = floatArrayOf(0f, 0f, 1f)
    private var alpha = 1f

    // Layout bands, measured in onSizeChanged: the wheel up top, then the value + alpha slider tracks.
    private val wheelRect = RectF()
    private var wheelCx = 0f
    private var wheelCy = 0f
    private var wheelRadius = 0f
    private val valueTrack = RectF()
    private val alphaTrack = RectF()

    private val sliderH get() = dp(18f)
    private val sliderGap get() = dp(14f)
    private val knobStroke get() = dp(2f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66000000")
    }
    private val checkerLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.parseColor("#555559") else Color.parseColor("#E4E4E8")
    }
    private val checkerDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (dark) Color.parseColor("#3A3A3E") else Color.parseColor("#C4C4C8")
    }

    private var hueShader: SweepGradient? = null

    /** Which region the current gesture is dragging, so a drag that leaves the region keeps control. */
    private enum class Drag { NONE, WHEEL, VALUE, ALPHA }
    private var activeDrag = Drag.NONE

    /** The picker's current colour as an ARGB int. */
    fun color(): Int = (Color.HSVToColor(hsv) and 0x00FFFFFF) or ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)

    /** Drive the control from an external ARGB [argb] (hex field / swatch) without re-emitting. */
    fun setColor(argb: Int) {
        Color.colorToHSV(argb, hsv)
        alpha = Color.alpha(argb) / 255f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Height = wheel (a square the width of the view) + two slider bands beneath it.
        val h = (w + (sliderH + sliderGap) * 2 + sliderGap).roundToInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = w.toFloat()
        val pad = dp(8f)
        wheelRadius = (size / 2f) - pad
        wheelCx = size / 2f
        wheelCy = pad + wheelRadius
        wheelRect.set(wheelCx - wheelRadius, wheelCy - wheelRadius, wheelCx + wheelRadius, wheelCy + wheelRadius)

        hueShader = SweepGradient(
            wheelCx, wheelCy,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
            ),
            null,
        )
        huePaint.shader = hueShader
        // A white→transparent radial for the saturation falloff (white centre → full-hue rim).
        satPaint.shader = RadialGradient(
            wheelCx, wheelCy, wheelRadius,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )

        val top = wheelCy + wheelRadius + sliderGap
        valueTrack.set(pad, top, size - pad, top + sliderH)
        val top2 = valueTrack.bottom + sliderGap
        alphaTrack.set(pad, top2, size - pad, top2 + sliderH)
    }

    override fun onDraw(canvas: Canvas) {
        // Hue wheel + saturation overlay, clipped to the circle.
        canvas.drawCircle(wheelCx, wheelCy, wheelRadius, huePaint)
        canvas.drawCircle(wheelCx, wheelCy, wheelRadius, satPaint)
        // A value (brightness) veil: darken the whole wheel as value drops, so the wheel reflects V.
        if (hsv[2] < 1f) {
            fillPaint.shader = null
            fillPaint.color = (Color.BLACK and 0x00FFFFFF) or (((1f - hsv[2]) * 255).roundToInt() shl 24)
            canvas.drawCircle(wheelCx, wheelCy, wheelRadius, fillPaint)
        }

        // Wheel knob at (hue angle, saturation radius).
        val angle = Math.toRadians(hsv[0].toDouble())
        val r = hsv[1] * wheelRadius
        val kx = wheelCx + (r * cos(angle)).toFloat()
        val ky = wheelCy + (r * sin(angle)).toFloat()
        drawKnob(canvas, kx, ky)

        drawValueSlider(canvas)
        drawAlphaSlider(canvas)
    }

    private fun drawValueSlider(canvas: Canvas) {
        // Black → the current fully-saturated hue: dragging it sets V.
        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))
        fillPaint.shader = LinearGradient(
            valueTrack.left, 0f, valueTrack.right, 0f, Color.BLACK, hueColor, Shader.TileMode.CLAMP,
        )
        val radius = valueTrack.height() / 2f
        canvas.drawRoundRect(valueTrack, radius, radius, fillPaint)
        fillPaint.shader = null
        val tx = valueTrack.left + hsv[2] * valueTrack.width()
        drawKnob(canvas, tx, valueTrack.centerY())
    }

    private fun drawAlphaSlider(canvas: Canvas) {
        val radius = alphaTrack.height() / 2f
        // Checkerboard behind the transparent→opaque gradient so opacity reads correctly.
        canvas.save()
        clipRound(canvas, alphaTrack, radius)
        drawChecker(canvas, alphaTrack)
        val opaque = color() or 0xFF000000.toInt()
        val transparent = opaque and 0x00FFFFFF
        fillPaint.shader = LinearGradient(
            alphaTrack.left, 0f, alphaTrack.right, 0f, transparent, opaque, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(alphaTrack, fillPaint)
        fillPaint.shader = null
        canvas.restore()
        val tx = alphaTrack.left + alpha * alphaTrack.width()
        drawKnob(canvas, tx, alphaTrack.centerY())
    }

    private fun clipRound(canvas: Canvas, rect: RectF, radius: Float) {
        val path = android.graphics.Path()
        path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        canvas.clipPath(path)
    }

    private fun drawChecker(canvas: Canvas, rect: RectF) {
        val cell = rect.height() / 2f
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left
            var col = 0
            while (x < rect.right) {
                val paint = if ((row + col) % 2 == 0) checkerLight else checkerDark
                canvas.drawRect(x, y, min(x + cell, rect.right), min(y + cell, rect.bottom), paint)
                x += cell; col++
            }
            y += cell; row++
        }
    }

    private fun drawKnob(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(9f)
        knobShadow.strokeWidth = knobStroke + dp(1.5f)
        canvas.drawCircle(cx, cy, r, knobShadow)
        knobPaint.strokeWidth = knobStroke
        canvas.drawCircle(cx, cy, r, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeDrag = when {
                    hypot(event.x - wheelCx, event.y - wheelCy) <= wheelRadius + dp(12f) -> Drag.WHEEL
                    event.y >= valueTrack.top - dp(14f) && event.y < alphaTrack.top - dp(2f) -> Drag.VALUE
                    event.y >= alphaTrack.top - dp(14f) -> Drag.ALPHA
                    else -> Drag.NONE
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                handleDrag(event)
            }
            MotionEvent.ACTION_MOVE -> handleDrag(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                activeDrag = Drag.NONE
            }
        }
        return true
    }

    private fun handleDrag(event: MotionEvent) {
        when (activeDrag) {
            Drag.WHEEL -> {
                val dx = event.x - wheelCx
                val dy = event.y - wheelCy
                var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (deg < 0) deg += 360f
                hsv[0] = deg
                hsv[1] = (hypot(dx, dy) / wheelRadius).coerceIn(0f, 1f)
            }
            Drag.VALUE -> hsv[2] = ((event.x - valueTrack.left) / valueTrack.width()).coerceIn(0f, 1f)
            Drag.ALPHA -> alpha = ((event.x - alphaTrack.left) / alphaTrack.width()).coerceIn(0f, 1f)
            Drag.NONE -> return
        }
        invalidate()
        onColorChanged(color())
    }
}
