package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.volume_plus_plus.app.MainActivity
import com.volume_plus_plus.app.data.OverlayCustomizationPrefs
import com.volume_plus_plus.app.i18n.Localization
import com.volume_plus_plus.app.i18n.shortLabel
import kotlin.math.roundToInt

/** The current translation. A getter, so these Views pick up a language change on their next build. */
private val strings get() = Localization.strings


/**
 * A WYSIWYG on-screen editor for one [OverlayVersion] + one [EditOrientation], in either
 * [LiveEditMode]. It keeps the app in the foreground, locks it to the orientation being edited, dims
 * its surface to a neutral backdrop, then draws the **real** overlay panel on top (via
 * [OverlayController] in live-edit mode) so what the user sees is exactly what will dock.
 *
 * - **Position:** drag the panel anywhere to move it; a tap still works its controls (preview only).
 * - **Colour:** tap a part of the panel (or a swatch chip) to pick the element, then dial its colour
 *   with R/G/B; the panel repaints live. The panel's controls stay interactive for preview.
 *
 * Either way the panel's controls never touch the device's real volume/ringer. A small floating
 * control bar (its own overlay window) carries the editing chrome — a Main/Expanded switch (Android
 * 9+), the mode-specific controls, and Cancel / Save. It parks itself clear of the panel and is
 * draggable if it's ever in the way.
 *
 * Locking the activity's orientation (rather than backgrounding to the home screen) makes landscape
 * editing reliable even when the launcher is portrait-locked — the app itself supplies the landscape
 * surface. Both orientations behave identically.
 *
 * Edits accumulate in an in-memory working copy; **Save** persists it to [OverlayCustomizationPrefs]
 * for this version alone, **Cancel** discards it. Either way the orientation lock is released and
 * [onFinished] runs.
 */
class LiveEditSession(
    private val activity: Activity,
    private val version: OverlayVersion,
    private val orientation: EditOrientation,
    private val mode: LiveEditMode,
    private val onFinished: () -> Unit,
) {
    private val appCtx = activity.applicationContext
    private val windowManager = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val customizationPrefs = OverlayCustomizationPrefs(appCtx)
    private val density = appCtx.resources.displayMetrics.density
    private val dark = isSystemDark()
    private val accent = Color.parseColor("#485D92")
    private val onBar = if (dark) Color.parseColor("#ECECEC") else Color.parseColor("#1B1B1F")
    private val baseStyle = styleFor(version.skin, dark)

    private val config = LiveEditConfig(
        version = version,
        orientation = orientation,
        working = customizationPrefs.getFor(version),
        mode = mode,
    )
    private val volume = AppVolumeController(appCtx)
    private val controller = OverlayController(appCtx, volume, liveEdit = config)

    /** The neutral full-screen backdrop drawn behind the panel while editing. */
    private var backdrop: View? = null
    /** The floating control-bar window. */
    private var bar: View? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var componentButtons: Map<PanelComponent, TextView> = emptyMap()
    private var finished = false

    /** The collapsible body of the bar (everything below the always-visible Cancel/Save row) and the
     *  chevron that hides/shows it, so the bar can be shrunk right down to just its actions to reveal
     *  more of the panel being edited. */
    private var barBody: View? = null
    private var collapseToggle: TextView? = null
    private var barCollapsed = false
    /** The bar-window opacity cycle position (100% → 65% → 35%), so the panel can be seen through it. */
    private var barOpacityStep = 0

    // COLOR-mode state.
    private var selectedColor = EditableColor.BACKGROUND
    private var swatchChips: Map<EditableColor, View> = emptyMap()
    private var colorWheel: ColorWheelPicker? = null
    private var hexField: EditText? = null
    /** Guards the wheel/hex from echoing each other's programmatic updates back as fresh edits. */
    private var syncingColor = false
    /** Set while the user is part-way through typing a hex value, so a wheel drag or a swatch tap
     *  doesn't overwrite what they're in the middle of entering. Cleared on commit and whenever a
     *  different element is selected. */
    private var userTypingHex = false
    private var swatchRow: LinearLayout? = null

    /**
     * Keeps the hex field to characters [parseHex] can read — and, just as importantly, accepts them
     * exactly as typed.
     *
     * Handing an [InputFilter] a *different* string back (upper-casing what was typed, say) throws
     * away the IME's composing region, and keyboards respond to that by swallowing characters
     * seemingly at random. So anything already valid is passed straight through with null, in
     * whatever case it arrived; only genuine non-hex characters are dropped. Case is normalised
     * later by [syncHexField], on commit, when nothing is mid-composition.
     */
    private val hexDigitFilter = InputFilter { source, start, end, _, _, _ ->
        if ((start until end).all { source[it].isHexDigit() }) null
        else (start until end).filter { source[it].isHexDigit() }
            .map { source[it] }
            .joinToString("")
    }

    /** Both cases: [parseHex] reads either, and rejecting lower case here is what broke typing. */
    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // POSITION-mode state: the manual X/Y coordinate fields (dp). Kept in sync with dragging.
    private var xField: EditText? = null
    private var yField: EditText? = null
    /** Guards the X/Y fields against echoing a drag-driven update back as a manual edit. */
    private var syncingFields = false

    fun start() {
        // Lock the app to the orientation being edited so the panel is against a real portrait/
        // landscape surface, regardless of the launcher's own rotation lock.
        activity.requestedOrientation = if (orientation == EditOrientation.LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showBackdrop()
        config.onPanelBounds = { parkBarAwayFrom(it) }
        config.onComponentChanged = { onComponentChangedFromPanel(it) }
        config.onColorTapped = { onColorTappedInPanel(it) }
        config.onOffsetChanged = { offset -> updateCoordFields(offset) }
        if (mode == LiveEditMode.COLOR) selectedColor = visibleColors(version, config.component).first()
        controller.show()
        showControlBar()
    }

    private fun showBackdrop() {
        val view = View(appCtx).apply { setBackgroundColor(Color.parseColor("#B3000000")) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        disableForceDark(view)
        backdrop = view
        runCatching { windowManager.addView(view, lp) }
    }

    /** Opt a WindowManager-added view out of platform Force Dark / "extended" auto-dark so its colours
     *  aren't re-inverted (overlay windows don't inherit the activity theme's opt-out). No-op < API 29. */
    private fun disableForceDark(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.isForceDarkAllowed = false
        }
    }

    private fun px(dp: Int) = (dp * density).roundToInt()

    // ── floating control bar ──────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showControlBar() {
        val barBg = if (dark) Color.parseColor("#2A2A2E") else Color.parseColor("#FFFFFF")

        // A focusable overlay window swallows BACK, so the bar has to say what BACK means. Left
        // unhandled it would be a dead key here, and before the bar was focusable it was worse:
        // BACK fell through to the activity underneath and popped the editor screen out from under
        // a session that carried right on running.
        val panel = object : LinearLayout(appCtx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    // While the keyboard is up, BACK belongs to the keyboard.
                    if (hexField?.hasFocus() == true) dismissHexKeyboard() else cancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            // So clearFocus() on the hex field lands somewhere instead of bouncing straight back to
            // the only focusable child in the bar.
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(barBg)
                setStroke(maxOf(1, px(1)), if (dark) Color.parseColor("#444448") else Color.parseColor("#E0E0E4"))
            }
            val h = px(10); val v = px(8); setPadding(h, v, h, v)
            elevation = px(8).toFloat()
        }

        // Top row (always visible): grip + collapse chevron + spacer + Cancel/Save. The Cancel/Save
        // actions live here on their own so nothing — however many component chips a version has —
        // can ever crowd Save off the edge; the Main/Expanded/Output switch moves into the body below.
        val topRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(appCtx).apply {
            text = "⋮⋮"
            setTextColor(fade(onBar, 0.5f))
            textSize = 16f
            val p = px(6); setPadding(p, p, p, p)
        }
        attachBarDrag(handle)
        topRow.addView(handle)

        // Collapse/expand button: shrinks the bar to just this row (revealing more of the panel) and
        // back. Drawn as a large, obvious tonal chip so it clearly reads as a control (not stray text).
        val toggle = TextView(appCtx).apply {
            text = "▾"
            setTextColor(accent)
            textSize = 20f
            gravity = Gravity.CENTER
            val h = px(12); val v = px(6); setPadding(h, v, h, v)
            minWidth = px(46)
            background = GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(fade(accent, if (dark) 0.30f else 0.15f))
            }
            isClickable = true
            setOnClickListener { toggleBarCollapsed() }
        }
        collapseToggle = toggle
        topRow.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(2)
        })

        // Opacity toggle: cycles the whole bar's transparency so the panel behind it can be seen (on
        // top of dragging/collapsing). Lives in the always-visible top row, so it works when collapsed.
        val opacity = TextView(appCtx).apply {
            text = "◐"
            setTextColor(fade(onBar, 0.7f))
            textSize = 18f
            gravity = Gravity.CENTER
            val h = px(10); val v = px(6); setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { cycleBarOpacity() }
        }
        topRow.addView(opacity, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = px(4)
        })

        topRow.addView(View(appCtx), LinearLayout.LayoutParams(0, px(1), 1f)) // spacer
        topRow.addView(textAction(strings.cancel, onBar) { cancel() })
        topRow.addView(textAction(strings.save, accent, bold = true) { save() })
        panel.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Collapsible body: the component switch (Android 9+) plus the mode-specific controls.
        val body = LinearLayout(appCtx).apply { orientation = LinearLayout.VERTICAL }
        barBody = body

        if (editComponents().size > 1) {
            // The switch can hold up to three chips (Main / Expanded / Output on Android 15), so it
            // scrolls horizontally to stay reachable within the fixed-width bar.
            val switchRow = LinearLayout(appCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val buttons = LinkedHashMap<PanelComponent, TextView>()
            editComponents().forEach { comp ->
                val chip = TextView(appCtx).apply {
                    text = componentLabel(comp)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    val h = px(12); val v = px(8); setPadding(h, v, h, v)
                    isClickable = true
                    setOnClickListener { selectComponent(comp) }
                }
                buttons[comp] = chip
                switchRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = px(6)
                })
            }
            componentButtons = buttons
            refreshComponentButtons()
            body.addView(
                HorizontalScrollView(appCtx).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(switchRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = px(2); bottomMargin = px(2)
                },
            )
        }

        if (mode == LiveEditMode.POSITION) {
            buildPositionControls(body)
        } else {
            buildColorControls(body)
        }
        panel.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // The colour panel and the position panel (with its X/Y fields) both want a fixed, roomier
        // width; only the bare position bar wrapped its content. Widened so the Cancel/Save row has
        // ample room and Save is never clipped.
        val lp = WindowManager.LayoutParams(
            px(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // Both editors carry text fields that have to receive typed input — X/Y coordinates in
            // POSITION mode, the hex value in COLOUR mode — so neither bar can be NOT_FOCUSABLE: a
            // non-focusable window never takes input focus, so the IME never opens and the field is
            // read-only in practice. NOT_TOUCH_MODAL keeps touches *outside* the bar falling through
            // to the panel behind it, so the panel is still draggable and tap-to-pick still works
            // while the bar can take keyboard input; ADJUST_PAN lets the soft keyboard open over it.
            // Volume keys are unaffected: VolumeKeyService is an AccessibilityService, so it sees
            // them before any focused window does.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            gravity = Gravity.TOP or Gravity.START
            x = px(16)
            // The colour bar carries the tall HSV wheel, so anchor it near the top where it always
            // fits; the compact position bar sits low, out of the way of the panel it edits.
            y = if (mode == LiveEditMode.COLOR) px(24)
            else appCtx.resources.displayMetrics.heightPixels - px(120)
        }
        barLp = lp
        bar = panel
        disableForceDark(panel)
        runCatching { windowManager.addView(panel, lp) }
    }

    // ── position controls ───────────────────────────────────────────────────────────────────────────

    /**
     * The position editor's bar body: a short hint, an editable X / Y coordinate row (dp, +x = right,
     * +y = down — the same convention the drag uses), and a Reset action. The fields show the live
     * offset and update in real time while the panel is dragged; typing a value moves the panel to
     * match. Drag and manual entry stay in sync through [updateCoordFields] / [commitCoordFields].
     */
    private fun buildPositionControls(panel: LinearLayout) {
        val hint = TextView(appCtx).apply {
            text = strings.liveEditPositionHint
            setTextColor(fade(onBar, 0.7f))
            textSize = 12f
            val p = px(4); setPadding(px(6), p, px(6), p)
        }
        panel.addView(hint)

        val coordRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = px(4); setPadding(px(6), p, px(6), p)
        }
        val offset = config.offset()
        val x = coordField(offset.dxDp) { commitCoordFields() }
        val y = coordField(offset.dyDp) { commitCoordFields() }
        xField = x
        yField = y
        coordRow.addView(coordLabel("X"))
        coordRow.addView(x, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(10) })
        coordRow.addView(coordLabel("Y"))
        coordRow.addView(y, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(coordRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val resetRow = LinearLayout(appCtx).apply { orientation = LinearLayout.HORIZONTAL }
        resetRow.addView(textAction(strings.liveEditResetPosition, onBar) { resetOffset() })
        panel.addView(resetRow)
    }

    private fun coordLabel(text: String) = TextView(appCtx).apply {
        this.text = text
        setTextColor(fade(onBar, 0.6f))
        textSize = 13f
        setPadding(0, 0, px(6), 0)
    }

    /** A signed-integer dp field, seeded with [value] (rounded), committing on Done/focus-loss. */
    private fun coordField(value: Float, onCommit: () -> Unit): EditText = EditText(appCtx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(value.roundToInt().toString())
        setTextColor(onBar)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(px(6), px(4), px(6), px(4))
        maxLines = 1
        imeOptions = EditorInfo.IME_ACTION_DONE
        setBackgroundColor(if (dark) android.graphics.Color.parseColor("#3A3A3E") else android.graphics.Color.parseColor("#F0F0F3"))
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { onCommit(); true } else false
        }
        setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onCommit() }
    }

    /** Read both fields and move the panel to that offset (ignores the drag→field echo guard, since
     *  this is a genuine manual entry). Blank/garbage keeps the current value for that axis. */
    private fun commitCoordFields() {
        if (syncingFields) return
        val cur = config.offset()
        val dx = xField?.text?.toString()?.trim()?.toFloatOrNull() ?: cur.dxDp
        val dy = yField?.text?.toString()?.trim()?.toFloatOrNull() ?: cur.dyDp
        config.withOffset(PanelOffset(dx, dy))
        controller.applyLiveOffset() // repositions the panel; its onOffsetChanged refreshes the fields
    }

    /** Reflect a drag-driven (or reset) offset in the X/Y fields without triggering a re-commit. The
     *  guard stops the field's own focus/editor callbacks from bouncing the value straight back. */
    private fun updateCoordFields(offset: PanelOffset) {
        val x = xField ?: return
        val y = yField ?: return
        syncingFields = true
        // Don't fight the user mid-type: only the field that isn't focused is refreshed from the drag.
        if (!x.hasFocus()) x.setText(offset.dxDp.roundToInt().toString())
        if (!y.hasFocus()) y.setText(offset.dyDp.roundToInt().toString())
        syncingFields = false
    }

    // ── colour controls ─────────────────────────────────────────────────────────────────────────────

    private fun buildColorControls(panel: LinearLayout) {
        val hint = TextView(appCtx).apply {
            text = strings.liveEditColourHint
            setTextColor(fade(onBar, 0.7f))
            textSize = 12f
            setPadding(px(6), px(4), px(6), px(4))
        }
        panel.addView(hint)

        val swatches = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swatchRow = swatches
        // Wrapped in a horizontal scroller so a panel with the full colour set (up to the Android
        // 12–15 sheet's seven swatches) stays reachable within the fixed-width bar.
        val swatchScroll = HorizontalScrollView(appCtx).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(swatchScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(4)
        })
        rebuildSwatches()

        // The HSV colour wheel + value/alpha sliders — the primary, visual picker. Capped to a
        // compact width (and centred) rather than filling the bar, since its height tracks its width —
        // so the whole editor stays small, especially over the tall expanded sheet.
        val wheel = ColorWheelPicker(appCtx, dark) { argb -> onWheelColorChanged(argb) }
        colorWheel = wheel
        panel.addView(wheel, LinearLayout.LayoutParams(px(200), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Hex field, kept in lock-step with the wheel (edit either, both update).
        val hexRow = LinearLayout(appCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hexRow.addView(TextView(appCtx).apply {
            text = "#"
            setTextColor(fade(onBar, 0.7f))
            textSize = 15f
            setPadding(px(6), 0, px(2), 0)
        })
        val hex = EditText(appCtx).apply {
            // NO_SUGGESTIONS keeps the IME from offering autocorrect on what is never a word, and
            // CAP_CHARACTERS asks it to show A–F the way the field displays them. The filters keep
            // the content to at most eight hex digits, so anything the field can hold is something
            // [parseHex] can read.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(8), hexDigitFilter)
            maxLines = 1
            setTextColor(onBar)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(px(6), px(4), px(6), px(4))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundColor(if (dark) Color.parseColor("#3A3A3E") else Color.parseColor("#F0F0F3"))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitHexField(); true } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Select what's already there, so typing replaces the value instead of being
                    // inserted into it. Without this the field arrives holding six characters
                    // against an eight-character cap, so a user who taps in and starts typing gets
                    // two characters accepted and then silently blocked. Posted, because the tap
                    // that gave focus positions the caret immediately afterwards.
                    post { selectAll() }
                } else {
                    commitHexField()
                }
            }
            // Apply as the user types rather than waiting for Done: an overlay window's IME action
            // and focus-loss callbacks are both easy for the user to never trigger (tapping away
            // lands on the panel, not on another field), so neither is a dependable commit point.
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (syncingColor) return
                    userTypingHex = true
                    parseHex(s?.toString()?.trim()?.removePrefix("#").orEmpty())?.let { applyHexLive(it) }
                }
            })
        }
        hexField = hex
        hexRow.addView(hex, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(hexRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })

        val actions = LinearLayout(appCtx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(textAction(strings.liveEditUseDefault, onBar) { setColorValue(null) })
        actions.addView(textAction(strings.liveEditPickFromScreen, accent) { startEyedropper() })
        panel.addView(actions)

        refreshPickerFromSelection()
    }

    /** The wheel emitted a new colour — apply it and mirror it into the hex field. */
    private fun onWheelColorChanged(argb: Int) {
        if (syncingColor) return
        // Dialling the wheel is an unambiguous "I've finished with the text field": without this the
        // mid-type hold would still be set from an earlier tap on it, and the hex would sit frozen
        // while the wheel moved — the very thing the hold exists to avoid in the other direction.
        userTypingHex = false
        dismissHexKeyboard(commit = false)
        setColorValue(argb)
        syncHexField(argb)
    }

    /** Apply a colour the user typed to the panel and the wheel, leaving the field itself alone —
     *  it already holds exactly what they entered, and rewriting it would fight the caret. */
    private fun applyHexLive(argb: Int) {
        setColorValue(argb)
        colorWheel?.let { syncingColor = true; it.setColor(argb); syncingColor = false }
    }

    /** Finish an edit of the hex field (Done, or focus moving away): apply the value and hand the
     *  field back to [syncHexField]. Input too short to parse — or garbage — snaps back to the
     *  colour actually in force, so the field never sits on something that isn't true. */
    private fun commitHexField() {
        if (syncingColor) return
        userTypingHex = false
        val raw = hexField?.text?.toString()?.trim()?.removePrefix("#") ?: return
        val parsed = parseHex(raw)
        if (parsed == null) {
            // Restore the field to the current colour so it never sits on an unparseable value.
            syncHexField(colorOf(selectedColor))
            return
        }
        applyHexLive(parsed)
        syncHexField(parsed)
    }

    /** #RGB, #RRGGBB or #AARRGGBB (hash already stripped) → an opaque-or-alpha ARGB int, else null. */
    private fun parseHex(hex: String): Int? {
        val h = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("") // #RGB → #RRGGBB
            else -> hex
        }
        if (h.length != 6 && h.length != 8) return null
        val value = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) (0xFF000000.toInt() or value.toInt()) else value.toInt()
    }

    /**
     * Show [argb] in the hex field as #RRGGBB (or #AARRGGBB when it carries non-opaque alpha).
     *
     * Held off only while the user is actually mid-edit ([userTypingHex]) — never on view focus,
     * which an [EditText] keeps for as long as nothing else takes it, and nothing else in this bar
     * ever does. Gating on focus latched the field permanently after the first tap, freezing the
     * reading while the wheel, the swatches and the panel all carried on changing.
     */
    private fun syncHexField(argb: Int) {
        val field = hexField ?: return
        if (userTypingHex) return
        val text = if (Color.alpha(argb) == 255)
            String.format("%06X", argb and 0x00FFFFFF)
        else
            String.format("%08X", argb)
        if (field.text.toString() == text) return
        syncingColor = true
        field.setText(text)
        // Park the caret at the end rather than letting setText reset it to 0, so the field is ready
        // to type into if it happens to be focused.
        if (field.hasFocus()) field.setSelection(text.length)
        syncingColor = false
    }

    private fun rebuildSwatches() {
        val row = swatchRow ?: return
        row.removeAllViews()
        val chips = LinkedHashMap<EditableColor, View>()
        visibleColors(version, config.component).forEach { c ->
            val swatch = View(appCtx).apply {
                background = swatchDrawable(colorOf(c), selected = c == selectedColor)
                isClickable = true
                setOnClickListener { selectColor(c) }
            }
            chips[c] = swatch
            row.addView(swatch, LinearLayout.LayoutParams(px(30), px(30)).apply {
                marginEnd = px(8)
            })
        }
        swatchChips = chips
    }

    private fun swatchDrawable(color: Int, selected: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            if (selected) maxOf(2, px(2)) else maxOf(1, px(1)),
            if (selected) accent else if (dark) Color.parseColor("#666666") else Color.parseColor("#BBBBBB"),
        )
    }

    private fun colorOf(c: EditableColor): Int =
        config.colors().get(c) ?: seedBaseStyle().defaultColor(c, dark)

    /** The style a swatch's *default* seeds from. The Android 15 expanded sheet swaps in its own
     *  reference palette (matching the renderer's [buildStyle]) so its chips show the colours actually
     *  drawn; every other component seeds from the plain skin style. */
    private fun seedBaseStyle(): OverlayStyle =
        if (config.component == PanelComponent.EXPANDED && version.iconSet == IconSet.ANDROID_15)
            if (dark) baseStyle.withAndroid15SheetDark() else baseStyle.withAndroid15SheetLight()
        else baseStyle

    private fun selectColor(c: EditableColor) {
        // Close the field *before* the selection moves. Dropping focus commits whatever is in it,
        // and committing after the switch would write the colour typed for the old element onto the
        // new one. The keyboard also has to go: it sits right over the wheel the user is heading for.
        dismissHexKeyboard()
        selectedColor = c
        refreshSwatchStrokes()
        refreshPickerFromSelection()
    }

    private fun refreshSwatchStrokes() {
        swatchChips.forEach { (c, view) ->
            view.background = swatchDrawable(colorOf(c), selected = c == selectedColor)
        }
    }

    /** Point the wheel + hex field at the currently-selected element's colour, without re-emitting.
     *  A different element is now being edited, so any half-typed hex for the previous one is stale:
     *  drop the mid-type hold and let the field show what's actually selected. */
    private fun refreshPickerFromSelection() {
        val current = colorOf(selectedColor)
        userTypingHex = false
        syncingColor = true
        colorWheel?.setColor(current)
        syncingColor = false
        syncHexField(current)
    }

    private fun setColorValue(value: Int?) {
        config.withColor(selectedColor, value)
        controller.applyLiveColors()
        refreshSwatchStrokes()
        // Clearing back to the skin default: pull the picker + hex back to that default colour.
        if (value == null) refreshPickerFromSelection()
    }

    /** A tap inside the panel selected an element to recolour — reflect it in the bar. */
    private fun onColorTappedInPanel(c: EditableColor) {
        if (c !in visibleColors(version, config.component)) return
        selectColor(c)
    }

    // ── eyedropper ──────────────────────────────────────────────────────────────────────────────

    /**
     * Hand over to [ScreenColorPicker] so the user can take a colour off any screen on the device.
     *
     * The editor's own windows go away for the duration — they'd otherwise be the only thing in the
     * screenshot — but the session itself stays alive, so unsaved edits made before the pick are
     * still there when it comes back.
     */
    private fun startEyedropper() {
        if (finished) return
        dismissHexKeyboard()
        suspendChrome()
        ScreenColorPicker.start(
            activity = activity,
            onPicked = { argb ->
                resumeChrome()
                setColorValue(argb)
                colorWheel?.let { syncingColor = true; it.setColor(argb); syncingColor = false }
                syncHexField(argb)
            },
            onCancelled = { resumeChrome() },
        )
    }

    /**
     * Take the editor off screen without losing it. The bar is hidden rather than removed so its
     * dragged position, opacity step and collapsed state all survive; only the panel's window is
     * genuinely torn down, and [OverlayController.show] rebuilds it from the same live-edit state.
     *
     * The orientation lock is deliberately left in place: it only constrains this app's own window,
     * so it doesn't stop the user rotating whatever they navigate to, and releasing it would just
     * add a way for the activity to be recreated out from under a suspended session.
     */
    private fun suspendChrome() {
        bar?.visibility = View.GONE
        backdrop?.visibility = View.GONE
        controller.hide()
    }

    private fun resumeChrome() {
        if (finished) return
        controller.show()
        backdrop?.visibility = View.VISIBLE
        bar?.visibility = View.VISIBLE
        // show() re-adds the panel's window on top of the bar, same as a component switch does, so
        // Save and Cancel have to be lifted back above it.
        raiseBar()
        // Bring the app back in front of whatever the user wandered off to. Permitted from the
        // background because the app holds SYSTEM_ALERT_WINDOW, which is one of the platform's
        // documented exemptions from the background-activity-start restrictions — and the session
        // couldn't have started at all without that permission.
        runCatching {
            appCtx.startActivity(
                Intent(appCtx, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }
    }

    /**
     * Drop the soft keyboard when the hex field is no longer what the user is working in — left up
     * it covers the wheel, which is the thing they just tapped a swatch to go and dial.
     *
     * Losing focus is what commits the field, which is right when the user is moving on to another
     * element but wrong when the *wheel* is what took over: there the typed text is already stale,
     * and committing it would push it back into the wheel mid-drag and fight the finger. Passing
     * [commit] as false suppresses that through the same [syncingColor] guard [commitHexField]
     * already honours — the wheel's own value follows immediately either way.
     */
    private fun dismissHexKeyboard(commit: Boolean = true) {
        val field = hexField ?: return
        if (!field.hasFocus()) return
        userTypingHex = false
        if (commit) {
            field.clearFocus()
        } else {
            syncingColor = true
            field.clearFocus()
            syncingColor = false
        }
        val imm = appCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        runCatching { imm?.hideSoftInputFromWindow(bar?.windowToken, 0) }
    }

    // ── shared bar bits ─────────────────────────────────────────────────────────────────────────────

    private fun textAction(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit) =
        TextView(appCtx).apply {
            text = label
            setTextColor(color)
            textSize = 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            val h = px(12); val v = px(8); setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** The components this session lets the user switch between. The Android 15 output picker is a
     *  colour-only surface (a centred modal, not a docked panel), so it's offered in COLOR mode only —
     *  in POSITION mode only the panels that actually dock (Main / Expanded) are positioned. */
    private fun editComponents(): List<PanelComponent> =
        if (mode == LiveEditMode.POSITION)
            version.components().filter { it != PanelComponent.OUTPUT }
        else version.components()

    /** The switch-chip label for a component. */
    private fun componentLabel(component: PanelComponent): String = component.shortLabel(strings)

    /** In COLOR mode, dock the editor bar to the bottom while editing the Media output (its picker is
     *  parked at the top, so the bar stays clear of it), and back to the top for other components.
     *  Skipped once the user has dragged the bar themselves. */
    private fun parkColorBarForComponent() {
        if (mode != LiveEditMode.COLOR || userMovedBar) return
        val lp = barLp ?: return
        if (config.component == PanelComponent.OUTPUT) {
            lp.gravity = Gravity.BOTTOM or Gravity.START; lp.y = px(24)
        } else {
            lp.gravity = Gravity.TOP or Gravity.START; lp.y = px(24)
        }
        runCatching { windowManager.updateViewLayout(bar, lp) }
    }

    /** Collapse the bar down to just its Cancel/Save row (to see more of the panel), or expand it. */
    private fun toggleBarCollapsed() {
        barCollapsed = !barCollapsed
        barBody?.visibility = if (barCollapsed) View.GONE else View.VISIBLE
        collapseToggle?.text = if (barCollapsed) "▸" else "▾"
    }

    /** Step the *control bar's* opacity 100% → 65% → 35% → 100% (the Save/Cancel editing bar itself,
     *  never the volume panel), so the user can see the panel through it — on top of dragging or
     *  collapsing it out of the way. Applied to the bar view's own alpha, which renders reliably on
     *  every device (unlike the window-level LayoutParams.alpha). Survives the bar's remove/re-add in
     *  [raiseBar] since it's the same view instance. */
    private fun cycleBarOpacity() {
        val levels = floatArrayOf(1f, 0.65f, 0.35f)
        barOpacityStep = (barOpacityStep + 1) % levels.size
        bar?.alpha = levels[barOpacityStep]
    }

    private fun selectComponent(component: PanelComponent) {
        // setLiveComponent fires onComponentChanged → onComponentChangedFromPanel, which runs
        // afterComponentChange(); no need to call it again here.
        controller.setLiveComponent(component)
    }

    /** The panel changed component (a bar tap, or tapping tune to expand / DONE to collapse). */
    private fun onComponentChangedFromPanel(component: PanelComponent) {
        afterComponentChange()
    }

    private fun afterComponentChange() {
        // Switching component tears down and re-adds the panel window, which would otherwise land on
        // top of the control bar — re-raise the bar so Save/Cancel stay reachable.
        raiseBar()
        refreshComponentButtons()
        parkColorBarForComponent()
        if (mode == LiveEditMode.COLOR) {
            val vis = visibleColors(version, config.component)
            if (selectedColor !in vis) selectedColor = vis.first()
            rebuildSwatches()
            refreshPickerFromSelection()
        }
    }

    /**
     * Bring the control bar back to the top of the overlay window stack. Among same-type overlay
     * windows the most-recently-added sits highest, and rebuilding the panel re-adds its window above
     * the bar — so we remove and re-add the bar (posted, so it lands after the panel's rebuild) to keep
     * Save/Cancel always visible and tappable. The backdrop, added before both, stays behind.
     */
    private fun raiseBar() {
        val view = bar ?: return
        val lp = barLp ?: return
        view.post {
            if (finished) return@post
            runCatching { windowManager.removeView(view) }
            runCatching { windowManager.addView(view, lp) }
        }
    }

    private fun refreshComponentButtons() {
        componentButtons.forEach { (comp, chip) ->
            val active = comp == config.component
            chip.background = if (active) GradientDrawable().apply {
                cornerRadius = px(16).toFloat()
                setColor(fade(accent, if (dark) 0.32f else 0.16f))
            } else null
            chip.setTextColor(if (active) accent else fade(onBar, 0.75f))
            chip.setTypeface(chip.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /** Drag the whole control bar by its handle so the user can move it out of the way. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachBarDrag(handle: View) {
        var startX = 0
        var startY = 0
        var startRawX = 0f
        var startRawY = 0f
        handle.setOnTouchListener { _, event ->
            val lp = barLp ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    startRawX = event.rawX; startRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    userMovedBar = true
                    lp.x = (startX + (event.rawX - startRawX)).roundToInt()
                    lp.y = (startY + (event.rawY - startRawY)).roundToInt()
                    runCatching { windowManager.updateViewLayout(bar, lp) }
                    true
                }
                else -> true
            }
        }
    }

    private var userMovedBar = false

    /** Keep the bar clear of the panel: if the panel is in the lower half, park the bar up top; else
     *  park it at the bottom. Stops nudging once the user drags the bar themselves. */
    private fun parkBarAwayFrom(panelRect: Rect) {
        val lp = barLp ?: return
        val view = bar ?: return
        // The colour bar is a tall, deliberately top-anchored wheel — leave it put; only the compact
        // position bar auto-parks out of the panel's way.
        if (mode == LiveEditMode.COLOR) return
        if (userMovedBar) return
        val screenH = appCtx.resources.displayMetrics.heightPixels
        view.post {
            val barH = view.height.takeIf { it > 0 } ?: px(120)
            val panelInLowerHalf = panelRect.centerY() > screenH / 2
            lp.y = if (panelInLowerHalf) px(24) else screenH - barH - px(24)
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    // ── actions ─────────────────────────────────────────────────────────────────────────────────────

    private fun resetOffset() {
        config.withOffset(PanelOffset())
        controller.applyLiveOffset()
    }

    private fun save() {
        // Land any value still sitting in the hex field first — dropping focus is what commits it,
        // and if that only happened during finish() the user's last typed colour would be written
        // into the working copy just after it had been persisted, and so be lost.
        dismissHexKeyboard()
        customizationPrefs.setFor(version, config.working)
        finish()
    }

    private fun cancel() = finish()

    private fun finish() {
        if (finished) return
        finished = true
        // A pick still in flight has overlay windows of its own and a callback pointing back into
        // this session, neither of which can outlive it.
        ScreenColorPicker.cancel()
        dismissHexKeyboard()
        bar?.let { view -> runCatching { windowManager.removeView(view) } }
        bar = null
        controller.destroy()
        volume.destroy()
        backdrop?.let { view -> runCatching { windowManager.removeView(view) } }
        backdrop = null
        runCatching { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        onFinished()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun isSystemDark(): Boolean {
        val mode = appCtx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun fade(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    companion object {
        /** The process-lifetime holder for the active session. */
        @Volatile
        private var active: LiveEditSession? = null

        /**
         * Launch a live edit for [version] + [orientation] + [mode] over [activity] (kept in front and
         * locked to that orientation). Tears down any prior session first. [onFinished] runs on
         * Save/Cancel.
         */
        fun launch(
            activity: Activity,
            version: OverlayVersion,
            orientation: EditOrientation,
            mode: LiveEditMode,
            onFinished: () -> Unit,
        ) {
            active?.finish()
            val session = LiveEditSession(activity, version, orientation, mode) {
                active = null
                onFinished()
            }
            active = session
            session.start()
        }

        /** True while an on-screen edit is in progress. */
        val isActive: Boolean get() = active != null
    }
}
