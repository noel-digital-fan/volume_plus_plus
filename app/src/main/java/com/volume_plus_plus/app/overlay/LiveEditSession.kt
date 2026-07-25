package com.volume_plus_plus.app.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.volume_plus_plus.app.data.OverlayCustomizationPrefs
import kotlin.math.roundToInt

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
    private var swatchRow: LinearLayout? = null

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

        val panel = LinearLayout(appCtx).apply {
            orientation = LinearLayout.VERTICAL
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
        topRow.addView(textAction("Cancel", onBar) { cancel() })
        topRow.addView(textAction("Save", accent, bold = true) { save() })
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
            // The position editor's coordinate fields need to receive typed input, so its bar must be
            // focusable (unlike the colour bar, which only has taps/sliders and stays non-focusable so
            // keys pass through). NOT_TOUCH_MODAL keeps touches *outside* the bar falling through to
            // the panel behind it, so the panel is still draggable while the bar can take keyboard
            // input; ADJUST_RESIZE + the IME flag let the soft keyboard open over it.
            if (mode == LiveEditMode.POSITION)
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (mode == LiveEditMode.POSITION) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
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
            text = "Drag the panel, or type X / Y (dp)"
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
        resetRow.addView(textAction("Reset position", onBar) { resetOffset() })
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
            text = "Tap the panel or a swatch, then dial or type a colour"
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
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setTextColor(onBar)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(px(6), px(4), px(6), px(4))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundColor(if (dark) Color.parseColor("#3A3A3E") else Color.parseColor("#F0F0F3"))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { commitHexField(); true } else false
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitHexField() }
        }
        hexField = hex
        hexRow.addView(hex, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(hexRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(8)
        })

        val actions = LinearLayout(appCtx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(textAction("Use default", onBar) { setColorValue(null) })
        panel.addView(actions)

        refreshPickerFromSelection()
    }

    /** The wheel emitted a new colour — apply it and mirror it into the hex field. */
    private fun onWheelColorChanged(argb: Int) {
        if (syncingColor) return
        setColorValue(argb)
        syncHexField(argb)
    }

    /** Parse the hex field (#RGB / #RRGGBB / #AARRGGBB, with or without '#') and apply it. Invalid
     *  input is ignored and the field is snapped back to the current colour. */
    private fun commitHexField() {
        if (syncingColor) return
        val raw = hexField?.text?.toString()?.trim()?.removePrefix("#") ?: return
        val parsed = parseHex(raw)
        if (parsed == null) {
            // Restore the field to the current colour so it never sits on an unparseable value.
            syncHexField(colorOf(selectedColor))
            return
        }
        setColorValue(parsed)
        colorWheel?.let { syncingColor = true; it.setColor(parsed); syncingColor = false }
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

    /** Show [argb] in the hex field as #RRGGBB (or #AARRGGBB when it carries non-opaque alpha). */
    private fun syncHexField(argb: Int) {
        val field = hexField ?: return
        syncingColor = true
        val text = if (Color.alpha(argb) == 255)
            String.format("%06X", argb and 0x00FFFFFF)
        else
            String.format("%08X", argb)
        if (!field.hasFocus()) field.setText(text)
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
        selectedColor = c
        refreshSwatchStrokes()
        refreshPickerFromSelection()
    }

    private fun refreshSwatchStrokes() {
        swatchChips.forEach { (c, view) ->
            view.background = swatchDrawable(colorOf(c), selected = c == selectedColor)
        }
    }

    /** Point the wheel + hex field at the currently-selected element's colour, without re-emitting. */
    private fun refreshPickerFromSelection() {
        val current = colorOf(selectedColor)
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
    private fun componentLabel(component: PanelComponent): String = when (component) {
        PanelComponent.MAIN -> "Main"
        PanelComponent.EXPANDED -> "Expanded"
        PanelComponent.OUTPUT -> "Media output"
    }

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
        customizationPrefs.setFor(version, config.working)
        finish()
    }

    private fun cancel() = finish()

    private fun finish() {
        if (finished) return
        finished = true
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
