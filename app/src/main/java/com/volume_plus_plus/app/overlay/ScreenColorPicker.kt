package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.volume_plus_plus.app.i18n.Localization
import com.volume_plus_plus.app.service.ColorPickService
import kotlin.math.roundToInt

/** The current translation. A getter, so these Views pick up a language change on their next build. */
private val strings get() = Localization.strings


/**
 * Drives the whole "pick a colour off the screen" flow, from the moment the eyedropper is tapped in
 * the colour editor to the moment a colour (or nothing) comes back.
 *
 * It is a process-wide singleton because the flow deliberately outlives the app being in front: the
 * user leaves for another app entirely, and the only things that survive that are this object, the
 * foreground service and the overlay windows. The captured frame is held here as a field and never
 * put in an Intent — a full-screen ARGB frame runs to several megabytes and would blow the binder
 * transaction limit.
 *
 * The flow:
 * 1. [start] hides the editor and asks the host activity for screen-capture consent.
 * 2. [onConsentResult] starts [ColorPickService], which goes foreground and posts its notification.
 * 3. A floating pill (and the notification) wait while the user browses. Either one can trigger the
 *    grab or cancel.
 * 4. [onFrameCaptured] freezes that frame under a [MagnifierLoupeView] for the actual picking.
 * 5. Confirm or cancel hands the result back to the caller, which puts the editor back.
 */
object ScreenColorPicker {

    /** Implemented by the activity that can raise the system's screen-capture consent dialog. */
    interface Host {
        fun requestScreenCapture()
    }

    private var appCtx: Context? = null
    private var windowManager: WindowManager? = null
    private var onPicked: ((Int) -> Unit)? = null
    private var onCancelled: (() -> Unit)? = null

    /** The floating "Pick / Cancel" pill shown while the user browses for a colour. */
    private var pill: View? = null
    private var pillLp: WindowManager.LayoutParams? = null

    /** The full-screen picking surface, shown once a frame has been captured. */
    private var loupeWindow: View? = null
    private var frame: Bitmap? = null
    private var pickedColor = Color.BLACK

    private var running = false

    /** True while a pick is in flight, so a session tearing down can call [cancel] unconditionally. */
    val isActive: Boolean get() = running

    private val density: Float get() = appCtx?.resources?.displayMetrics?.density ?: 1f
    private fun px(dp: Int) = (dp * density).roundToInt()

    private fun dark(): Boolean {
        val ctx = appCtx ?: return true
        val mode = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Begin a pick over [activity], which must implement [Host]. [onPicked] receives the chosen
     * opaque ARGB; [onCancelled] runs on every other way out, so the caller can put its own UI back
     * exactly once whatever happens.
     */
    fun start(activity: Activity, onPicked: (Int) -> Unit, onCancelled: () -> Unit) {
        if (running) return
        val host = activity as? Host
        if (host == null) {
            onCancelled()
            return
        }
        appCtx = activity.applicationContext
        windowManager = activity.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.onPicked = onPicked
        this.onCancelled = onCancelled
        running = true
        host.requestScreenCapture()
    }

    /** The consent dialog answered. Anything but a grant ends the flow before it really began. */
    fun onConsentResult(resultCode: Int, data: android.content.Intent?) {
        val ctx = appCtx ?: return
        if (!running) return
        if (resultCode != Activity.RESULT_OK || data == null) {
            toast(strings.eyedropperNeedsPermission)
            cancel()
            return
        }
        ColorPickService.start(ctx, resultCode, data)
    }

    /** The service is foreground and holding the projection — put the floating control up. */
    fun onCaptureReady() {
        if (running) showPill()
    }

    /** Take the pill off screen entirely (not merely hidden) so it can't appear in its own capture. */
    fun hideChromeForCapture() {
        removePill()
    }

    fun onCaptureFailed() {
        toast(strings.eyedropperCaptureFailed)
        cancel()
    }

    /** A frame arrived: freeze it under the loupe and let the user pick. */
    fun onFrameCaptured(bitmap: Bitmap) {
        if (!running) {
            bitmap.recycle()
            return
        }
        frame = bitmap
        if (isAllBlack(bitmap)) {
            // A secure window (banking apps, DRM video) captures as a black rectangle. Saying so
            // beats letting the user carefully pick #000000 and wonder why.
            toast(strings.eyedropperBlocked)
            cancel()
            return
        }
        showLoupe(bitmap)
    }

    /** The service stopped for a reason of its own (cancel action, revoked capture, screen lock). */
    fun onServiceStopped() {
        if (running && loupeWindow == null) cancel()
    }

    // ── the browsing pill ───────────────────────────────────────────────────────────────────────

    /**
     * A small draggable overlay carrying the same two actions as the notification. It exists because
     * the notification can't be relied on: `POST_NOTIFICATIONS` may be refused outright, and from
     * Android 14 an ongoing foreground-service notification can be swiped away regardless. This the
     * app can genuinely keep on screen until the user is done.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showPill() {
        val ctx = appCtx ?: return
        val wm = windowManager ?: return
        if (pill != null) return
        val dark = dark()
        val onBar = if (dark) Color.parseColor("#ECECEC") else Color.parseColor("#1B1B1F")
        val accent = Color.parseColor("#485D92")

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(if (dark) Color.parseColor("#2A2A2E") else Color.parseColor("#FFFFFF"))
                setStroke(maxOf(1, px(1)), if (dark) Color.parseColor("#444448") else Color.parseColor("#E0E0E4"))
            }
            elevation = px(8).toFloat()
            val h = px(6); val v = px(4); setPadding(h, v, h, v)
        }
        disableForceDark(row)

        val handle = TextView(ctx).apply {
            text = "⋮⋮"
            setTextColor(fade(onBar, 0.5f))
            textSize = 16f
            val p = px(6); setPadding(p, p, p, p)
        }
        attachDrag(handle)
        row.addView(handle)
        row.addView(action(ctx, "◎ " + strings.eyedropperPick, accent, bold = true) {
            ColorPickService.send(ctx, ColorPickService.ACTION_PICK)
        })
        row.addView(action(ctx, strings.cancel, onBar) {
            ColorPickService.send(ctx, ColorPickService.ACTION_CANCEL)
            cancel()
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Non-focusable so every key and every touch outside the pill goes to whatever app the
            // user is browsing — they need it fully usable while they hunt for the colour.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(16)
            y = px(80)
        }
        pill = row
        pillLp = lp
        runCatching { wm.addView(row, lp) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = pillLp ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startRawX = event.rawX; startRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (event.rawX - startRawX)).roundToInt()
                    lp.y = (startY + (event.rawY - startRawY)).roundToInt()
                    runCatching { windowManager?.updateViewLayout(pill, lp) }
                }
            }
            true
        }
    }

    private fun removePill() {
        val view = pill ?: return
        runCatching { windowManager?.removeView(view) }
        pill = null
        pillLp = null
    }

    // ── the picking surface ─────────────────────────────────────────────────────────────────────

    /**
     * The frozen frame under a [MagnifierLoupeView], with a bottom bar to confirm or back out.
     *
     * Force Dark is switched off explicitly: an overlay window doesn't inherit the activity theme's
     * opt-out, and letting the platform re-tint the screenshot would mean every colour picked came
     * back subtly wrong.
     */
    private fun showLoupe(bitmap: Bitmap) {
        val ctx = appCtx ?: return
        val wm = windowManager ?: return
        val dark = dark()
        val onBar = if (dark) Color.parseColor("#ECECEC") else Color.parseColor("#1B1B1F")
        val accent = Color.parseColor("#485D92")

        val hint = TextView(ctx)
        val loupe = MagnifierLoupeView(ctx, bitmap) { argb ->
            pickedColor = argb
            hint.text = strings.eyedropperDragToPick("#%06X".format(argb and 0x00FFFFFF))
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(if (dark) Color.parseColor("#2A2A2E") else Color.parseColor("#FFFFFF"))
            }
            elevation = px(8).toFloat()
            val h = px(10); val v = px(6); setPadding(h, v, h, v)
        }
        hint.apply {
            setTextColor(fade(onBar, 0.75f))
            textSize = 12f
            setPadding(px(6), 0, px(6), 0)
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(action(ctx, strings.cancel, onBar) {
            ColorPickService.send(ctx, ColorPickService.ACTION_CANCEL)
            cancel()
        })
        bar.addView(action(ctx, strings.eyedropperUseColour, accent, bold = true) { confirm() })

        val rootView = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    ColorPickService.send(ctx, ColorPickService.ACTION_CANCEL)
                    cancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        rootView.addView(loupe, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        rootView.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM
            val m = px(16); setMargins(m, m, m, px(40))
        })
        disableForceDark(rootView)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            // Focusable, so BACK reaches the root view above and reads as "cancel" rather than
            // dropping the user out of whatever app happens to be underneath the frozen frame.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        loupeWindow = rootView
        runCatching { wm.addView(rootView, lp) }
    }

    private fun confirm() {
        val argb = pickedColor
        val callback = onPicked
        teardown()
        callback?.invoke(argb)
    }

    /** Every way out that isn't a confirmed colour. Safe to call more than once, and safe to call
     *  when nothing is running — [LiveEditSession] does exactly that on teardown. */
    fun cancel() {
        if (!running) return
        val callback = onCancelled
        teardown()
        callback?.invoke()
    }

    private fun teardown() {
        running = false
        removePill()
        loupeWindow?.let { view -> runCatching { windowManager?.removeView(view) } }
        loupeWindow = null
        frame?.recycle()
        frame = null
        onPicked = null
        onCancelled = null
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Cheap sample of the corners and centre — enough to spot a screen that captured as all black
     *  without walking a few million pixels. */
    private fun isAllBlack(bitmap: Bitmap): Boolean {
        val xs = intArrayOf(1, bitmap.width / 2, bitmap.width - 2)
        val ys = intArrayOf(1, bitmap.height / 4, bitmap.height / 2, bitmap.height * 3 / 4, bitmap.height - 2)
        for (x in xs) for (y in ys) {
            if (bitmap.getPixel(x, y) and 0x00FFFFFF != 0) return false
        }
        return true
    }

    private fun action(
        ctx: Context,
        label: String,
        color: Int,
        bold: Boolean = false,
        onClick: () -> Unit,
    ) =
        TextView(ctx).apply {
            text = label
            setTextColor(color)
            textSize = 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val h = px(12); val v = px(8); setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun toast(message: String) {
        appCtx?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    private fun fade(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun disableForceDark(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.isForceDarkAllowed = false
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
}
