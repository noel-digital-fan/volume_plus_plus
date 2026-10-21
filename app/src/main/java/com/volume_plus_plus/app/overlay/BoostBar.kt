package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.volume_plus_plus.app.audio.BoostPolicy
import com.volume_plus_plus.app.i18n.Localization
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The above-100% indicator that appears while [MediaBooster] is applying gain.
 *
 * Deliberately **not** a [VolumeSlider]. A slider's level means "where in this stream's range the
 * volume sits", 0..1 by definition, and every skin's renderer, the drag hit-testing and the reserved
 * percentage column are all built on that meaning. Boost is a different quantity — extra gain on top
 * of a stream already at its maximum — so stretching `level` to 1.2 would have meant rescaling every
 * draw path by 1/1.2 and shrinking the 0–100% range on every skin for every user, including the vast
 * majority who never switch the booster on. This is the cheaper and more honest shape.
 *
 * Non-interactive: only the volume keys move it, so it consumes no touches and the panel's
 * tap-outside-to-dismiss keeps working over it.
 */
@SuppressLint("ViewConstructor")
class BoostBar(
    context: Context,
    private val fillColor: Int,
    private val trackColor: Int,
    private val labelColor: Int,
    private val showLabel: Boolean,
    /** Vertical for the collapsed side panels, whose media control is itself a vertical capsule. */
    private val vertical: Boolean = false,
) : View(context) {

    /** 0f..1f across the boost range. */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** What the label reads, e.g. 120 for "120%". */
    var percent: Int = 100
        set(value) {
            field = value
            invalidate()
        }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = dp(11f)
        isFakeBoldText = true
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val long = dp(LENGTH_DP).roundToInt()
        val thick = dp(HEIGHT_DP).roundToInt()
        setMeasuredDimension(
            resolveSize(if (vertical) thick else long, widthMeasureSpec),
            resolveSize(if (vertical) long else thick, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (vertical) drawVertical(canvas) else drawHorizontal(canvas)
    }

    private fun drawHorizontal(canvas: Canvas) {
        val track = dp(TRACK_DP)
        val cy = height / 2f
        // The label keeps its own reserved column, so the track never changes length as the number
        // goes from 105% to the maximum configurable ceiling.
        val labelW = if (showLabel) labelPaint.measureText(fullScaleLabel()) + dp(6f) else 0f
        val right = width - labelW
        val radius = track / 2f

        barPaint.color = trackColor
        rect.set(0f, cy - radius, right, cy + radius)
        canvas.drawRoundRect(rect, radius, radius, barPaint)

        if (fraction > 0f) {
            barPaint.color = fillColor
            // Never shorter than a full round cap, so the first notch reads as a bar rather than a
            // clipped sliver.
            val filled = min(right, (right * fraction).coerceAtLeast(track))
            rect.set(0f, cy - radius, filled, cy + radius)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }

        if (showLabel) {
            labelPaint.color = labelColor
            val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(
                Localization.strings.percent(percent), width.toFloat(), baseline, labelPaint,
            )
        }
    }

    /** Fills upward, so the boost grows away from the media capsule it sits above. */
    private fun drawVertical(canvas: Canvas) {
        val track = dp(TRACK_DP)
        val cx = width / 2f
        val radius = track / 2f

        barPaint.color = trackColor
        rect.set(cx - radius, 0f, cx + radius, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, barPaint)

        if (fraction > 0f) {
            barPaint.color = fillColor
            val filled = min(height.toFloat(), (height * fraction).coerceAtLeast(track))
            rect.set(cx - radius, height - filled, cx + radius, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }

    /** Measured against the widest value this can ever show, so the column is stable. */
    private fun fullScaleLabel(): String =
        Localization.strings.percent(BoostPolicy.percent(BoostPolicy.HARD_MAX_STEPS))

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )

    private companion object {
        /** Across the bar (its height when horizontal, its width when vertical). */
        const val HEIGHT_DP = 18f
        /** Along the bar, when nothing constrains it. */
        const val LENGTH_DP = 44f
        const val TRACK_DP = 6f
    }
}
