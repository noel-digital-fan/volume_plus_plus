package com.volume_plus_plus.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.volume_plus_plus.app.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Chevron shown at the trailing edge of a row: none, or an up/down expand-collapse caret. */
enum class ChevronDir { NONE, UP, DOWN }

/**
 * A slider that mimics a real volume-panel bar, rendered entirely from an [OverlayStyle].
 *
 * - [SliderRender.FILL_PILL]: a rounded bar that fills to the level (vertical bottom→top, horizontal
 *   start→end), icon at the trailing/leading edge.
 * - [SliderRender.LINE_THUMB]: a thin track with a round thumb — horizontal (icon at the leading
 *   edge, optional label above, optional trailing chevron — the Android 7–8 row look) or vertical
 *   (a bare line + thumb, used for the Android 9–11 collapsed media slider).
 *
 * [orientation]/[fill]/[reserveTrailing] can override the style so one [OverlayStyle] can drive both
 * a vertical collapsed slider and horizontal expanded rows. Drag anywhere on the bar to set level.
 */
@SuppressLint("ViewConstructor")
class VolumeSlider(
    context: Context,
    private val style: OverlayStyle,
    private val icon: Drawable?,
    private val glyph: String?,
    private val label: String?,
    chevron: ChevronDir,
    private val orientation: SliderOrientation = style.orientation,
    private val fill: Boolean = style.stretch,
    private val reserveTrailing: Boolean = false,
    private val render: SliderRender = style.render,
    /**
     * [CAPSULE] only: draw the track as a full-width rounded pill (the whole slider footprint) instead
     * of the thin centre line — so the fill capsule sits inside a solid shaped background, the Android
     * 15 main media-slider look. Left `false` keeps the historic thin stick (Android 12–14).
     */
    private val capsuleFullTrack: Boolean = false,
    /**
     * [PILL_LABEL] only: give the *fill* a straight (square) leading edge instead of the default
     * rounded cap — the Android 15 media-output "This phone" pill. The outer track keeps its rounded
     * corners (the fill is still clipped to them), so only the growing edge reads flat.
     */
    private val squareFill: Boolean = false,
    /** [PILL_LABEL] only: the output-picker layout — show the live volume "NN%" pinned to the far
     *  left (left of the icon), then the icon, then the label; the fill stays fully rounded. */
    private val percentWhileDragging: Boolean = false,
    /**
     * [PILL_LABEL] only: recolour the leading icon to contrast with its backing (fill vs. track).
     * Left `false` for full-colour app icons, which must not be flattened to a single tint.
     */
    private val recolorIcon: Boolean = true,
    /** [PILL_LABEL] only: the icon painted once the level reaches zero (e.g. a crossed speaker). */
    private val mutedIcon: Drawable? = null,
    /**
     * [PILL_LABEL] picker only: explicit colours that override the [style] palette so the output
     * picker can match its own spec — the track (background), the fill, and a single "content"
     * colour used for both the label/percentage text and the trailing dot, over fill and track alike.
     */
    private val pickerTrackColor: Int? = null,
    private val pickerFillColor: Int? = null,
    private val pickerContentColor: Int? = null,
    /** PILL_LABEL only: extra fill width (dp) applied only when level is non-zero. */
    private val minNonZeroFillExtraDp: Float = 0f,
    /** PILL_LABEL only: fade the row label out while dragging (driven by [dragProgress]). */
    private val fadeLabelWhileDragging: Boolean = false,
    /** Horizontal [LINE_THUMB] only: draw a translucent ripple halo behind the thumb while this row
     *  is being dragged (the Android 7–8 pressed-seekbar look). */
    private val pressedHalo: Boolean = false,
    /**
     * When false the slider ignores touch entirely (never consumes it, never changes level) while
     * rendering exactly as normal — used by the editors, whose preview panels are visual-only. Unlike
     * [sliderEnabled] this is invisible: no grey, no hollow thumb. The touch falls through so the
     * editor's drag / colour-tap layer can still handle it.
     */
    private val interactive: Boolean = true,
    /** Scale factor for the faster part of the held-volume glide (1 = current default). */
    private val motionFollowScale: Float = 1f,
    /** Scale factor for the final settle once the glide is close to target (1 = current default). */
    private val motionSettleScale: Float = 1f,
    private val onLevelChange: (Float) -> Unit,
    private val onTouchStart: () -> Unit,
    private val onTouchEnd: () -> Unit,
    private val onChevronClick: () -> Unit = {},
) : View(context) {

    private val vertical = orientation == SliderOrientation.VERTICAL
    private val lineThumb = render == SliderRender.LINE_THUMB
    private val capsule = render == SliderRender.CAPSULE
    private val pillLabel = render == SliderRender.PILL_LABEL

    /** 0 = idle (fully rounded fill) … 1 = dragging (fill's leading edge squared off). Animated. */
    private var dragProgress = 0f
    private var dragAnimator: ValueAnimator? = null

    /** Animated display value for the slider fill/thumb. */
    private var drawnLevel = 1f
    private var targetLevel = 1f
    private var levelGlideActive = false
    private val levelGlideRunnable = object : Runnable {
        override fun run() {
            if (!levelGlideActive) return
            val diff = targetLevel - drawnLevel
            val distance = abs(diff)
            if (distance <= 0.001f) {
                drawnLevel = targetLevel
                levelGlideActive = false
                invalidate()
                return
            }
            val gain = when {
                distance > 0.35f -> 0.52f
                distance > 0.20f -> 0.40f
                distance > 0.08f -> 0.30f
                else -> 0.20f
            }
            val scale = if (distance > 0.08f) motionFollowScale else motionSettleScale
            drawnLevel += diff * gain * scale
            if (abs(targetLevel - drawnLevel) <= 0.0005f) drawnLevel = targetLevel
            invalidate()
            if (drawnLevel != targetLevel) postOnAnimation(this) else levelGlideActive = false
        }
    }
    private var draggingLevel = false

    /**
     * 0 = label fully shown … 1 = label fully faded (dragging). Kept separate from [dragProgress] so
     * the label can crossfade on its own gentle timeline while the fill's corner still squares off
     * snappily — driven only when [fadeLabelWhileDragging] is set.
     */
    private var labelFade = 0f
    private var labelFadeAnimator: ValueAnimator? = null

    /** True once the slider's level has reached the muted floor. */
    private fun isMutedLevel(): Boolean = mutedIcon != null && level <= 0.001f

    /** The icon to paint now: the muted variant once the level hits zero, else the normal icon. */
    private val drawnIcon: Drawable?
        get() = if (isMutedLevel()) mutedIcon else icon

    /** Current fill fraction, 0f (empty) .. 1f (full). */
    var level: Float
        get() = drawnLevel
        set(value) {
            setLevelInternal(value.coerceIn(0f, 1f), animate = !draggingLevel)
        }

    /**
     * When false the slider is locked: its fill/thumb take the inactive grey, the thumb collapses to
     * a small hollow ring (the real Android 7–8 disabled-seekbar "double circle") and touch can no
     * longer change the level; the trailing chevron, if any, still responds (the Android 7–8 Ring
     * row while "Alarms only" is active).
     */
    var sliderEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Greyed while *another* row is being adjusted (the Android 7–8 look: touch one stream and the
     * rest grey out). Like the real panel only the slider recolours — fill and thumb turn the
     * inactive grey while the icon and label keep their normal tint. Unlike [sliderEnabled] touch
     * still works, it just never happens while a sibling is being dragged.
     */
    var dimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** True while this row's own thumb is being dragged — drives the [pressedHalo] ripple. */
    private var dragging = false

    /**
     * Notified when this row starts (true) / stops (false) being dragged, so the owning panel can
     * dim the sibling rows for the duration of the gesture. Set after construction.
     */
    var onDragStateChange: ((Boolean) -> Unit)? = null

    /**
     * The trailing expand/collapse chevron's direction. Mutable so a panel can flip it (DOWN⇄UP)
     * in place when it expands/collapses instead of rebuilding the row — the width it reserves is
     * the same for DOWN and UP, so only a repaint is needed.
     */
    var chevronDir: ChevronDir = chevron
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density * OVERLAY_SCALE

    // NOTE: use also{it...} not apply{} — inside apply the receiver's own `style` member (Paint.style)
    // would shadow our `style` constructor param.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = pickerTrackColor ?: style.trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = pickerFillColor ?: style.fillColor }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.thumbColor }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = style.pressedHaloColor }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = style.iconTint
        it.textAlign = Paint.Align.CENTER
        it.textSize = dp(18f)
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = style.iconTint
        it.textAlign = Paint.Align.CENTER
        it.textSize = dp(13f)
    }
    private val labelZone get() = if (label != null) dp(22f) else 0f

    /** Bold label drawn inside/over a [PILL_LABEL] fill (recoloured per-draw to match its backing). */
    private val pillLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        it.textSize = dp(14f)
        it.typeface = Typeface.DEFAULT_BOLD
    }

    private val clipPath = Path()
    private val bounds = RectF()

    // Insets for horizontal LINE_THUMB rows: icon column on the left (only if an icon is present),
    // chevron column on the right. The chevron column is reserved when this row has a chevron OR
    // [reserveTrailing] is set, so sibling rows without a chevron still align their track ends.
    private val iconColW get() = if (icon != null) dp(44f) else 0f
    private val chevronColW
        get() = if (lineThumb && (reserveTrailing || chevronDir != ChevronDir.NONE)) dp(40f) else 0f
    // Keep the track ends at least a thumb-radius (+margin) inside the view so the thumb circle is
    // never clipped by the view edge at 0% / 100% (rows without a leading icon start at x = 0).
    private val thumbInset get() = dp(9f)
    private fun trackLeft() = maxOf(iconColW, thumbInset)
    private fun trackRight() = width - maxOf(chevronColW, thumbInset)
    private fun trackTop() = labelZone + dp(10f)
    private fun trackBottom() = height - dp(10f)

    /** True while the current gesture started on the chevron (so it toggles instead of dragging). */
    private var chevronCapture = false

    // Horizontal rows (the Sound-sheet seekbars) live inside a vertical scroller. To avoid nudging
    // the volume when the user really means to scroll, we defer: on a scrollable row we don't grab
    // the gesture or move the level until we know the drag is horizontal. A vertical drag is left for
    // the scroller to consume.
    private val scrollableRow = !vertical && (lineThumb || pillLabel)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    /** For a scrollable row: whether this gesture's direction has been resolved yet. */
    private var directionDecided = false
    /** For a scrollable row: whether we committed the gesture to a (horizontal) volume drag. */
    private var horizontalClaim = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val thickness = dp(style.pillThicknessDp.toFloat()).toInt()
        val length = dp(style.pillLengthDp.toFloat()).toInt()
        when {
            vertical && fill -> setMeasuredDimension(thickness, MeasureSpec.getSize(heightMeasureSpec))
            !vertical && fill -> {
                // A stretched row fills the width; its height honours an EXACT height from the parent
                // (Sound-sheet seekbar rows) and otherwise falls back to the style thickness (the
                // Android 7–8 top-sheet rows, which size themselves).
                val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
                    MeasureSpec.getSize(heightMeasureSpec) else thickness
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
            }
            vertical -> setMeasuredDimension(thickness, length)
            else -> setMeasuredDimension(length, thickness)
        }
    }

    override fun onDraw(canvas: Canvas) {
        when {
            capsule -> drawCapsule(canvas)
            pillLabel -> drawPillLabel(canvas)
            lineThumb && vertical -> drawLineThumbVertical(canvas)
            lineThumb -> drawLineThumbHorizontal(canvas)
            else -> drawFillPill(canvas)
        }
    }

    // ── PILL_LABEL (Android 15 sheet row): filled pill, icon + adaptive label, trailing dot ─────────

    private fun drawPillLabel(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        // A picker supplies one content colour for text + dot regardless of backing (its fill and
        // track are both light). Otherwise a skin may pin explicit per-side content colours (the
        // Android 15 dark sheet does), and failing that the content falls back to the historic
        // automatic on-fill colour (black/white by the fill's luminance) so it stays legible and
        // doesn't track any editable colour, and to the editable text colour over the track.
        val onFill = pickerContentColor ?: style.pillContentOnFill ?: contrastOn(pickerFillColor ?: style.fillColor)
        val onTrack = pickerContentColor ?: style.pillContentOnTrack ?: style.textColor
        val iconSize = dp(ICON_DP)

        // Track pill — always fully rounded ends (the outer shape never changes).
        val trackRadius = h / 2f
        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, trackRadius, trackRadius, trackPaint)

        // Keep Android 15's leading circular indicator visible at mute, then grow proportionally from
        // that base so 1%, 2%, 3%... and 97–100% remain visually distinct.
        val baseCircle = h
        val fillEnd = baseCircle + (w - baseCircle) * level
        val nonZeroExtra = if (level > 0.001f) dp(minNonZeroFillExtraDp) else 0f
        val fillRight = (fillEnd + nonZeroExtra).coerceIn(0f, w)
        // A square-fill pill draws its leading edge flat (radius 0); it's still clipped to the rounded
        // track below, so the outer corners stay rounded and only the growing edge reads straight.
        val fillRadius = when {
            squareFill -> 0f
            percentWhileDragging -> trackRadius
            else -> trackRadius * (1f - dragProgress) + dp(3f) * dragProgress
        }
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(bounds, trackRadius, trackRadius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        canvas.drawRoundRect(0f, 0f, fillRight, h, fillRadius, fillRadius, fillPaint)
        canvas.restore()

        // Trailing dot near the end (Android 15's per-row affordance). An explicit dotColor override
        // pins it to that exact colour on both fill and track; otherwise it follows the on-backing
        // content colour (legible over whichever side it sits on), as before.
        val dotCx = w - dp(16f)
        pillLabelPaint.color = style.dotColor ?: if (fillRight >= dotCx) onFill else onTrack
        canvas.drawCircle(dotCx, cy, dp(2f), pillLabelPaint)

        if (percentWhileDragging) {
            drawPickerContent(canvas, cy, iconSize, fillRight, dotCx, onFill, onTrack)
        } else {
            drawSheetLabel(canvas, cy, iconSize, fillRight, dotCx, onFill, onTrack)
        }
    }

    /**
     * Output-picker pill: normally a leading icon then the device name. While the volume is being
     * adjusted the icon's spot instead shows the live "NN%" — the two crossfade via [dragProgress]
     * (1 = dragging), so the percentage only appears while dragging. The leading column is sized for
     * whichever is wider, so the device name never shifts as the icon swaps to the percentage.
     */
    private fun drawPickerContent(
        canvas: Canvas, cy: Float, iconSize: Float, fillRight: Float, dotCx: Float,
        onFill: Int, onTrack: Int,
    ) {
        val baseline = cy - (pillLabelPaint.descent() + pillLabelPaint.ascent()) / 2f
        val leadLeft = dp(14f)
        pillLabelPaint.textAlign = Paint.Align.CENTER
        val leadColW = maxOf(iconSize, pillLabelPaint.measureText("100%"))
        // Keep the icon pinned to the pill's left content edge while still reserving the wider
        // percentage column so the trailing label never shifts during the icon/percent crossfade.
        val leadCx = leadLeft + iconSize / 2f
        val percentBaseline = baseline - dp(0.5f)
        val overFillLead = fillRight >= leadCx

        // Once muted, keep the crossed icon visible immediately even during drag so it never feels
        // delayed behind the percent crossfade.
        val forceMutedIcon = isMutedLevel()
        if (forceMutedIcon) {
            drawIcon(canvas, leadCx, cy, iconSize, overFillLead, onFill, onTrack, 255)
        } else {
            // Icon (idle) fading out and the percentage (dragging) fading in, sharing the leading
            // column.
            if (dragProgress < 1f) {
                drawIcon(canvas, leadCx, cy, iconSize, overFillLead, onFill, onTrack,
                    ((1f - dragProgress) * 255).roundToInt())
            }
            if (dragProgress > 0f) {
                pillLabelPaint.color = if (overFillLead) onFill else onTrack
                pillLabelPaint.alpha = (dragProgress * 255).roundToInt()
                canvas.drawText("${(level * 100).roundToInt()}%", leadCx, percentBaseline, pillLabelPaint)
                pillLabelPaint.alpha = 255
            }
        }

        // Device name after the leading column.
        val text = label ?: return
        if (text.isEmpty()) return
        pillLabelPaint.textAlign = Paint.Align.LEFT
        val x = leadLeft + leadColW + dp(10f)
        val dotLeft = dotCx - dp(6f)
        pillLabelPaint.color = if (fillRight >= x + pillLabelPaint.measureText(text)) onFill else onTrack
        pillLabelPaint.alpha =
            if (fadeLabelWhileDragging) ((1f - labelFade) * 255).roundToInt().coerceIn(0, 255) else 255
        val shown = TextUtils
            .ellipsize(text, pillLabelPaint, (dotLeft - x).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            .toString()
        canvas.drawText(shown, x, baseline, pillLabelPaint)
        pillLabelPaint.alpha = 255
    }

    /** Sheet stream pill: leading icon then the stream label (inside the fill, or just past it). */
    private fun drawSheetLabel(
        canvas: Canvas, cy: Float, iconSize: Float, fillRight: Float, dotCx: Float,
        onFill: Int, onTrack: Int,
    ) {
        val iconCx = dp(14f) + iconSize / 2f
        val iconRight = iconCx + iconSize / 2f
        drawIcon(canvas, iconCx, cy, iconSize, fillRight >= iconCx, onFill, onTrack)

        val text = label ?: return
        if (text.isEmpty()) return
        pillLabelPaint.textAlign = Paint.Align.LEFT
        val baseline = cy - (pillLabelPaint.descent() + pillLabelPaint.ascent()) / 2f
        val textW = pillLabelPaint.measureText(text)
        val minX = iconRight + dp(8f)
        val dotLeft = dotCx - dp(6f)
        val labelLeft = if (icon != null) minX else dp(16f)
        val x: Float
        val color: Int
        if (fillRight >= labelLeft + textW + dp(6f)) {
            x = labelLeft; color = onFill
        } else {
            x = maxOf(labelLeft, fillRight + dp(10f)); color = onTrack
        }
        pillLabelPaint.color = color
        pillLabelPaint.alpha =
            if (fadeLabelWhileDragging) ((1f - labelFade) * 255).roundToInt().coerceIn(0, 255) else 255
        val shown = TextUtils
            .ellipsize(text, pillLabelPaint, (dotLeft - x).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            .toString()
        canvas.drawText(shown, x, baseline, pillLabelPaint)
        pillLabelPaint.alpha = 255
    }

    /** Draw [drawnIcon] centred at [cx], recoloured to contrast its backing (unless [recolorIcon]). */
    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, overFill: Boolean, onFill: Int, onTrack: Int, alpha: Int = 255) {
        val it = drawnIcon ?: return
        it.colorFilter =
            if (recolorIcon) PorterDuffColorFilter(if (overFill) onFill else onTrack, PorterDuff.Mode.SRC_IN) else null
        it.alpha = alpha
        val half = size / 2f
        it.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        it.draw(canvas)
        it.alpha = 255
    }

    // ── CAPSULE (Android 12–15 media slider): thin stick (12–14) or full pill (15) track + capsule ──

    private fun drawCapsule(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val lineW = dp(4f)

        // Track behind everything: a full-width rounded pill spanning the whole slider (the Android 15
        // main look), or the historic thin centre line (Android 12–14) — both with rounded ends.
        if (capsuleFullTrack) {
            val trackR = w / 2f
            canvas.drawRoundRect(0f, 0f, w, h, trackR, trackR, trackPaint)
        } else {
            canvas.drawRoundRect(cx - lineW / 2f, 0f, cx + lineW / 2f, h, lineW / 2f, lineW / 2f, trackPaint)
        }

        // Build the capsule from a moving circular head plus trailing body. The head travels linearly
        // with level so every increment, including edge steps, has a distinct position.
        val r = w / 2f
        val travel = (h - 2f * r).coerceAtLeast(0f)
        val headCy = h - r - (travel * level)
        val top = headCy - r
        canvas.drawRoundRect(0f, top, w, h, r, r, fillPaint)

        // Icon stays perfectly centered on the slider axis and follows the moving head.
        val iconSize = dp(ICON_DP)
        drawIconAt(canvas, cx, headCy, iconSize)
    }

    // ── FILL_PILL ───────────────────────────────────────────────────────────────────────────────

    private fun drawFillPill(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val thickness = if (vertical) w else h
        val radius = thickness * style.pillCornerFraction

        bounds.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(bounds, trackPaint)
        if (vertical) canvas.drawRect(0f, h * (1f - level), w, h, fillPaint)
        else canvas.drawRect(0f, 0f, w * level, h, fillPaint)
        canvas.restore()

        drawEdgeIcon(canvas, w, h)
    }

    private fun drawEdgeIcon(canvas: Canvas, w: Float, h: Float) {
        val iconSize = dp(ICON_DP)
        val cx = if (vertical) w / 2f else iconSize / 2f + dp(10f)
        val cy = if (vertical) h - iconSize / 2f - dp(12f) else h / 2f
        drawIconAt(canvas, cx, cy, iconSize)
    }

    // ── LINE_THUMB (horizontal row) ───────────────────────────────────────────────────────────────

    private fun drawLineThumbHorizontal(canvas: Canvas) {
        // An inactive bar (locked, or greyed because a sibling row is being dragged) recolours its
        // fill and thumb to the inactive grey — the track, icon and label keep their normal colours,
        // matching the real Android 7–8 rows.
        val inactive = !sliderEnabled || dimmed
        fillPaint.color = if (inactive) style.inactiveColor else (pickerFillColor ?: style.fillColor)
        thumbPaint.color = if (inactive) style.inactiveColor else style.thumbColor
        val h = height.toFloat()
        // Label sits at the top; the icon + track + thumb live in the zone below it.
        if (label != null) {
            // The label may span the whole row (up to the track's right edge, before the chevron),
            // not just the narrow icon column. Centre it over the icon when it fits, but slide it
            // inward so it never runs past either edge; ellipsize only if still too wide.
            val leftEdge = dp(6f)
            val rightEdge = trackRight()
            val text = TextUtils
                .ellipsize(label, labelPaint, rightEdge - leftEdge, TextUtils.TruncateAt.END)
                .toString()
            val half = labelPaint.measureText(text) / 2f
            val minCx = leftEdge + half
            val maxCx = (rightEdge - half).coerceAtLeast(minCx)
            val cx = (iconColW / 2f).coerceIn(minCx, maxCx)
            val baseline = labelZone / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(text, cx, baseline, labelPaint)
        }
        val cy = labelZone + (h - labelZone) / 2f
        val left = trackLeft()
        val right = trackRight()
        val lineH = dp(2f)

        // Track line.
        canvas.drawRoundRect(left, cy - lineH / 2f, right, cy + lineH / 2f, lineH, lineH, trackPaint)
        // Filled portion up to the thumb.
        val thumbX = left + (right - left) * level
        canvas.drawRoundRect(left, cy - lineH / 2f, thumbX, cy + lineH / 2f, lineH, lineH, fillPaint)
        // Ripple halo behind the thumb while this row is the one being dragged.
        if (pressedHalo && dragging && sliderEnabled) canvas.drawCircle(thumbX, cy, dp(15f), haloPaint)
        // Thumb: a filled circle normally; locked rows collapse it to a small hollow ring (the
        // Android 7–8 disabled-seekbar "double circle", e.g. Ring while "Alarms only" is active).
        if (sliderEnabled) {
            canvas.drawCircle(thumbX, cy, dp(7f), thumbPaint)
        } else {
            thumbPaint.style = Paint.Style.STROKE
            thumbPaint.strokeWidth = dp(2f)
            canvas.drawCircle(thumbX, cy, dp(4.5f), thumbPaint)
            thumbPaint.style = Paint.Style.FILL
        }
        // Leading icon (only if this row carries one).
        if (icon != null || glyph != null) drawIconAt(canvas, iconColW / 2f, cy, dp(ICON_DP))
        // Trailing expand/collapse chevron.
        if (chevronDir != ChevronDir.NONE) drawChevron(canvas, width - chevronColW / 2f, cy)
    }

    // ── LINE_THUMB (vertical, bare line + thumb) ──────────────────────────────────────────────────

    private fun drawLineThumbVertical(canvas: Canvas) {
        val cx = width / 2f
        val top = dp(10f)
        val bottom = height - dp(10f)
        val lineW = dp(2f)

        canvas.drawRoundRect(cx - lineW / 2f, top, cx + lineW / 2f, bottom, lineW, lineW, trackPaint)
        val thumbY = bottom - (bottom - top) * level
        canvas.drawRoundRect(cx - lineW / 2f, thumbY, cx + lineW / 2f, bottom, lineW, lineW, fillPaint)
        canvas.drawCircle(cx, thumbY, dp(7f), thumbPaint)
    }

    // The material expand_less/expand_more glyphs — the caret the real Android 7–8 panel used —
    // tinted like the row icons. Lazy: only rows that actually carry a chevron load them.
    private val chevronUp by lazy { loadChevron(R.drawable.ic_expand_up) }
    private val chevronDown by lazy { loadChevron(R.drawable.ic_expand_down) }

    private fun loadChevron(res: Int): Drawable? =
        ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(style.iconTint) }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float) {
        val glyph = (if (chevronDir == ChevronDir.UP) chevronUp else chevronDown) ?: return
        val half = dp(12f)
        glyph.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        glyph.draw(canvas)
    }

    // ── shared ──────────────────────────────────────────────────────────────────────────────────

    /** A legible on-[background] colour (black or white by luminance) for content drawn over a fill. */
    private fun contrastOn(background: Int): Int {
        val lum = (0.299 * android.graphics.Color.red(background) +
            0.587 * android.graphics.Color.green(background) +
            0.114 * android.graphics.Color.blue(background)) / 255.0
        return if (lum > 0.5) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    private fun drawIconAt(canvas: Canvas, cx: Float, cy: Float, iconSize: Float) {
        val drawable = drawnIcon
        if (drawable != null) {
            drawable.setBounds(
                (cx - iconSize / 2f).toInt(),
                (cy - iconSize / 2f).toInt(),
                (cx + iconSize / 2f).toInt(),
                (cy + iconSize / 2f).toInt(),
            )
            drawable.draw(canvas)
        } else if (glyph != null) {
            val baseline = cy - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
            canvas.drawText(glyph, cx, baseline, glyphPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Editor previews are visual-only: don't consume the touch so it falls through to the drag /
        // colour-tap layer, and never move the level.
        if (!interactive) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // A tap that starts on the chevron toggles expand/collapse instead of dragging.
                chevronCapture = chevronDir != ChevronDir.NONE && event.x >= width - chevronColW
                if (chevronCapture) { parent?.requestDisallowInterceptTouchEvent(true); return true }
                if (scrollableRow) {
                    // Don't claim the gesture or move the level yet — wait to see which way it drags.
                    downX = event.x; downY = event.y
                    directionDecided = false
                    horizontalClaim = false
                    return true
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                beginDrag()
                updateFromTouch(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (chevronCapture) return true
                if (scrollableRow) {
                    if (!directionDecided) {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (dx < touchSlop && dy < touchSlop) return true // below slop: undecided
                        directionDecided = true
                        horizontalClaim = dx >= dy
                        if (horizontalClaim) {
                            // Horizontal → it's a volume drag; lock out the scroller and start moving.
                            parent?.requestDisallowInterceptTouchEvent(true)
                            beginDrag()
                            updateFromTouch(event)
                        }
                        // Vertical → leave it: we never disallowed intercept, so the scroller steals
                        // the gesture and we'll receive ACTION_CANCEL.
                        return true
                    }
                    if (horizontalClaim) updateFromTouch(event)
                    return true
                }
                updateFromTouch(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                when {
                    chevronCapture -> {
                        if (event.action == MotionEvent.ACTION_UP && event.x >= width - chevronColW) {
                            // The chevron tap is handled here rather than via performClick, so play the
                            // system touch-click ourselves — this is what gives Android 8's expand/collapse
                            // the same click sound as Android 7's floating chevron. Honours the view's
                            // isSoundEffectsEnabled and the device's touch-sound setting.
                            playSoundEffect(SoundEffectConstants.CLICK)
                            onChevronClick()
                        }
                        chevronCapture = false
                    }
                    scrollableRow -> {
                        // A tap that never became a drag sets the level at the touch point.
                        if (event.action == MotionEvent.ACTION_UP && !directionDecided) {
                            beginDrag(); updateFromTouch(event); finishDrag()
                        } else if (horizontalClaim) {
                            finishDrag()
                        }
                        directionDecided = false
                        horizontalClaim = false
                    }
                    else -> finishDrag()
                }
            }
        }
        return true
    }

    private fun beginDrag() {
        draggingLevel = true
        levelGlideActive = false
        removeCallbacks(levelGlideRunnable)
        dragging = true
        invalidate()
        onTouchStart()
        onDragStateChange?.invoke(true)
        // A sheet pill squares off its fill's leading edge; a picker pill swaps its icon for "NN%".
        // Both are driven by [dragProgress] (1 = dragging), so animate it for any PILL_LABEL slider.
        if (pillLabel) animateDrag(1f)
        if (fadeLabelWhileDragging) animateLabelFade(1f)
    }

    private fun finishDrag() {
        draggingLevel = false
        dragging = false
        invalidate()
        onTouchEnd()
        onDragStateChange?.invoke(false)
        if (pillLabel) animateDrag(0f)
        if (fadeLabelWhileDragging) animateLabelFade(0f)
    }

    /**
     * Drive [dragProgress] toward [target] (1 = dragging): a sheet pill squares off its fill's
     * leading edge, a picker pill crossfades its icon to the live percentage. Reverses on release.
     */
    private fun animateDrag(target: Float) {
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(dragProgress, target).apply {
            val fadingOut = target > dragProgress
            // Start fading labels as soon as drag is committed; keep it smooth but snappy to avoid a
            // visible linger at gesture start.
            duration = if (fadingOut) 90L else 160L
            interpolator = if (fadingOut) DecelerateInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener {
                dragProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Drive [labelFade] toward [target] (1 = hidden). Deliberately slower and gentler than the fill's
     * [animateDrag] — a soft symmetric ease over a longer window so the label dissolves and returns
     * without the abrupt pop of being tied to the snappy fill timeline. Resolves to exactly [target]
     * so the label can't be left partly faded if a gesture ends mid-animation.
     */
    private fun animateLabelFade(target: Float) {
        labelFadeAnimator?.cancel()
        labelFadeAnimator = ValueAnimator.ofFloat(labelFade, target).apply {
            // A short remaining distance gets a proportionally shorter time, so rapid grab/release
            // flicks stay responsive instead of always running the full window.
            val span = abs(target - labelFade).coerceAtLeast(0.001f)
            duration = ((if (target > labelFade) 220L else 300L) * span).toLong().coerceAtLeast(60L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                labelFade = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateFromTouch(event: MotionEvent) {
        if (!sliderEnabled) return
        val newLevel = when {
            lineThumb && vertical -> (trackBottom() - event.y) / (trackBottom() - trackTop())
            lineThumb -> (event.x - trackLeft()) / (trackRight() - trackLeft())
            vertical -> 1f - event.y / height
            else -> event.x / width
        }
        val clamped = newLevel.coerceIn(0f, 1f)
        if (clamped != level) {
            setLevelInternal(clamped, animate = false)
            onLevelChange(clamped)
        }
    }

    private fun setLevelInternal(target: Float, animate: Boolean) {
        targetLevel = target
        if (target == drawnLevel && !levelGlideActive) return
        if (!animate || !isAttachedToWindow || !(pillLabel || capsule)) {
            levelGlideActive = false
            removeCallbacks(levelGlideRunnable)
            drawnLevel = target
            invalidate()
            return
        }
        if (!levelGlideActive) {
            levelGlideActive = true
            postOnAnimation(levelGlideRunnable)
        }
    }

    private companion object {
        const val ICON_DP = 24f
    }
}
