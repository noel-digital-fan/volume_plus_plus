package com.volume_plus_plus.app.overlay

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.app.NotificationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.data.OverlayCustomizationPrefs
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.data.ThemePrefs
import com.volume_plus_plus.app.shizuku.ShizukuManager
import com.volume_plus_plus.app.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds and manages the floating volume panel. The look comes entirely from the user-selected
 * [OverlaySkin] (via [OverlayPrefs] → [styleFor]).
 *
 * Two families of layout exist:
 * - The generic list renderer (Android 7–8): a single styled container of sliders. It's expandable —
 *   both versions collapse to just the Media row (it's what the hardware volume keys drive) and
 *   expand to add Ring and Alarm — plus one slider per currently-playing non-system app. Android 8's
 *   expanded order is Media / Ring / Alarm, so Media's own chevron carries the toggle; Android 7's
 *   expanded order is Ring / Media / Alarm (icon-only rows, no labels), so its toggle instead lives
 *   in [GenericChevronToggle], floating over the trailing space every row already reserves for a
 *   chevron rather than sitting in any one row, so it never moves and adds no extra height. The Ring
 *   row drives Notification too (merged streams), and while the ring is muted it greys out behind an
 *   "Alarms only" footer.
 * - Android 9–11: a bespoke two-state layout — a right-edge collapsed panel (ringer button +
 *   vertical media slider + tune footer) that expands into a bottom "Sound" sheet with every stream,
 *   per-app sliders, and SEE MORE / DONE.
 *
 * Owned by [com.volume_plus_plus.app.service.VolumeKeyService], which runs in the app process so
 * this can call [ShizukuManager] directly.
 *
 * Per-app slider levels are read from and written back through [appVolume], which persists each
 * app's chosen volume for the lifetime of its playback session and re-applies it as players churn —
 * so a level survives screen off/on and other transient interruptions instead of resetting to full.
 */
class OverlayController(
    private val context: Context,
    private val appVolume: AppVolumeController,
    /**
     * When non-null the controller runs in embedded-preview mode: it renders into [PreviewConfig.host]
     * instead of the WindowManager, uses synthetic volumes, never auto-hides, and applies the
     * in-progress customization — see [PreviewConfig]. Null for the real overlay.
     */
    private val preview: PreviewConfig? = null,
    /**
     * When non-null the controller runs in **live-edit** mode: the real panel is drawn on the screen
     * via the WindowManager (like the live overlay) but is draggable to reposition, never dismisses
     * itself, and uses synthetic volumes — a WYSIWYG position editor. See [LiveEditConfig]. Mutually
     * exclusive with [preview]; null for the real overlay.
     */
    private val liveEdit: LiveEditConfig? = null,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    private val prefs = OverlayPrefs(context)
    private val customizationPrefs = OverlayCustomizationPrefs(context)
    private val themePrefs = ThemePrefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density * OVERLAY_SCALE).toInt()
    private fun dp(v: Float) = (v * density * OVERLAY_SCALE).roundToInt()

    // ── skin / version resolution ───────────────────────────────────────────────────────────────────
    // In preview mode the edited version fixes the skin + sub-version; otherwise they come from prefs.

    /** The version pinned by an active editor (preview or live-edit), or null for the real overlay. */
    private fun editVersion(): OverlayVersion? = preview?.version ?: liveEdit?.version

    /** True in either editor mode (embedded preview or on-screen live-edit). The preview panels are
     *  visual-only: their controls perform no action, so every navigation/dismiss handler and the
     *  sliders short-circuit while an editor is active. */
    private fun isEditor(): Boolean = preview != null || liveEdit != null

    private fun curSkin(): OverlaySkin = editVersion()?.skin ?: prefs.getSkin()
    private fun curIconSet(): IconSet = editVersion()?.iconSet ?: prefs.getIconSet()
    private fun curSevenEight(): SevenEightVersion =
        editVersion()?.sevenEightVersion ?: prefs.getSevenEightVersion()
    /** The ringer mode to draw — the device's real one outside an editor, since every local ringer
     *  write in [setRinger] completes inside its binder call, so reading the mode straight back is
     *  both instant and honest. The one exception is [pendingSilent], the privileged silent request
     *  that has to cross into another process; it shows for as long as that call is in flight and
     *  reconciles the moment it settles, so a tap still repaints immediately. */
    private fun curRingerMode(): Int = preview?.ringerMode ?: liveEdit?.ringerMode ?: when {
        pendingSilent -> AudioManager.RINGER_MODE_SILENT
        else -> audioManager.ringerMode
    }

    /** A privileged silent request is in flight (see [silenceRinger]). */
    private var pendingSilent = false

    /** The mode last asked for while this panel has been open — not always the one the platform
     *  granted (see [silenceRinger]). [cycleRinger] steps from this rather than from the live mode, so
     *  a refused mode is stepped past on the next tap instead of being retried forever. */
    private var requestedRingerMode: Int? = null

    // ── customization (position + colour) ───────────────────────────────────────────────────────────

    /** The customization for the version being shown — the in-progress one in preview/live-edit, else
     *  the saved one for the user's selected style. */
    private fun currentCustomization(): VersionCustomization =
        preview?.customization ?: liveEdit?.working ?: customizationPrefs.getFor(OverlayVersion.current(prefs))

    /** Which component the current render represents: in live-edit the one being positioned; otherwise
     *  the expanded sheet for a rich panel that's expanded, else the main panel. Drives which
     *  colour/position set applies. */
    private fun activeComponent(): PanelComponent = liveEdit?.component
        ?: if (isRichPanel() && expanded) PanelComponent.EXPANDED else PanelComponent.MAIN

    /** Whether the panel is being laid out for landscape — the device's real orientation normally, or
     *  the orientation the editor is previewing/editing. */
    private fun curOrientation(): EditOrientation = preview?.orientation ?: liveEdit?.orientation
        ?: if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            EditOrientation.LANDSCAPE else EditOrientation.PORTRAIT

    /** The position offset to apply to the panel now (active component × current orientation). */
    private fun activeOffset(): PanelOffset =
        currentCustomization().component(activeComponent()).offset(curOrientation())

    /** The raw colour overrides for the active component (each null = use the element's own default).
     *  Read directly (not via [buildStyle]) where an element has a bespoke default that isn't one of
     *  the [OverlayStyle] palette colours — e.g. the output picker's reference blue palette. */
    private fun activeColors(): PanelColors =
        currentCustomization().component(activeComponent()).colors

    /** The base skin style with the active component's colour overrides layered on. The Android 15
     *  expanded sheet swaps in its own dark reference palette first (see [withAndroid15SheetDark]) so
     *  its untouched dark defaults match the media-output picker; user overrides still layer on top. */
    private fun buildStyle(): OverlayStyle {
        val base = styleFor(curSkin(), isDark())
        val themed = when {
            expanded && isAndroid15Sheet() ->
                if (isDark()) base.withAndroid15SheetDark() else base.withAndroid15SheetLight()
            else -> base
        }
        return themed.applyColors(activeColors())
    }

    /** The full-screen transparent scrim added to the window manager; catches off-panel taps. */
    private var root: ViewGroup? = null
    /** The actual panel, hosted inside [root]; this is what gets populated and slide-animated. */
    private var panel: ViewGroup? = null
    // A placeholder; every render()/show() rebuilds it from the current skin + customization.
    private var style: OverlayStyle = styleFor(curSkin(), isDark())
    private var expanded = false
    /** The Android 15 "Audio will play on" output-picker screen is open (a sub-screen of the sheet). */
    private var outputPicker = false
    /** Whether the ringer-mode pop-up menu is open (Material You collapsed panel only). */
    private var ringerMenuOpen = false
    /** True while the panel is playing its slide-off dismissal, so it isn't restarted. */
    private var dismissing = false
    /** The media (STREAM_MUSIC) slider currently on screen, so volume-key presses can update it in
     *  place instead of rebuilding the whole panel (which flickers, esp. the Sound sheet). */
    private var mediaSlider: VolumeSlider? = null
    /** The Android 9–11 ringer button currently on screen, so a mode change can retint it in place
     *  instead of rebuilding the whole panel (which cancels the app-sync load and drops frames, and
     *  is what made rapid mode taps feel laggy). */
    private var ringerIcon: ImageView? = null
    /** The Android 12–15 ringer mode buttons currently on screen, for the same in-place repaint —
     *  held here as well as in the builder's closure so [refreshRingerViews] can reach them when a
     *  privileged silent request lands after the tap that started it. */
    private var ringerButtons: Map<Int, ImageView>? = null
    private var loadJob: Job? = null

    /** The Android 15 media-output picker modal, added over the (dimmed) Sound sheet without tearing
     *  the window down, so it expands/collapses smoothly instead of a close-and-reopen. */
    private var pickerOverlay: FrameLayout? = null
    /** The sheet's media slider, parked while the picker is open so closing can restore volume-key
     *  control to it (the picker's own "This phone" pill takes over meanwhile). */
    private var savedSheetMediaSlider: VolumeSlider? = null
    /** The Android 15 Notification row's two states, swapped live as the ring is muted/unmuted. */
    private var notifSlider: VolumeSlider? = null
    private var notifSliderRow: View? = null
    private var notifDisabledRow: View? = null
    /** The Android 7–8 Ring bar and its "Alarms only" footer, swapped live as the ring mutes/unmutes. */
    private var genericRingSlider: VolumeSlider? = null
    private var alarmsOnlyRow: View? = null
    /** Android 7's own expand/collapse chevron, floating over Ring/Media/Alarm's reserved trailing
     *  space so it never moves and takes no row of its own — unlike Android 8 (where Media is already
     *  first and stays put), Android 7's expanded order puts Ring above Media, so the chevron can't
     *  live on either of those rows without visually jumping when the other one shows/hides. Null on
     *  Android 8, which keeps its chevron on the Media row itself. */
    private var genericToggle: GenericChevronToggle? = null
    /** Android 7–8 rows in display order + the app box, and the subset hidden while collapsed, so an
     *  expand/collapse just toggles their visibility (animated) instead of rebuilding the panel. */
    private var generic78OrderedRows: List<View> = emptyList()
    private var generic78ExtraRows: List<View> = emptyList()
    private var generic78AppsBox: LinearLayout? = null
    /** The Android 7–8 row currently "selected": it stays highlighted while every other row stays
     *  greyed, until a different row is selected. Media is the default (and re-selected whenever the
     *  hardware volume keys are used). Null only before the first generic panel is built. */
    private var generic78Selected: VolumeSlider? = null

    /**
     * The per-app slider box currently on screen and the renderer that builds one row for it, shared
     * by every skin. While a panel is open [appsSyncRunnable] re-queries the active players on a timer
     * and diffs against these rows: apps that stopped playing are removed and newly-playing apps are
     * appended, so controls never go stale and existing rows (and their set volumes) are left intact.
     */
    private var appsBox: LinearLayout? = null
    private var appBuilder: ((AppPlayers) -> View)? = null
    private val appsSyncRunnable = Runnable { syncAppSliders(schedule = true) }

    /**
     * Package of the app currently in the foreground, fed by the accessibility service on window
     * changes. When that app is among those playing audio it's surfaced first in the per-app list
     * ("prioritise the currently active app"); null when unknown. Read off the main thread by
     * [queryApps], so it's volatile.
     */
    @Volatile
    var foregroundPackage: String? = null

    /** Window signature that, when it changes, forces the overlay window to be re-created. */
    private var currentSig: String? = null

    private val hideRunnable = Runnable { dismissWithAnimation() }

    /** Ease-out sliding in, ease-in sliding out — one shared pair so the panel's entrance and exit
     *  feel identical on every skin and every supported Android version (API 24–37). */
    private val enterInterpolator = DecelerateInterpolator()
    private val exitInterpolator = AccelerateInterpolator()

    /** One app that's actively playing, with every player id so a change hits all of them. */
    private data class AppPlayers(
        val pkg: String,
        val label: String,
        val icon: Drawable?,
        val piids: List<Int>,
    )

    /** Nudge the media stream in [direction] (+1 up, -1 down), then show/refresh the panel. */
    fun adjustAndShow(direction: Int): Boolean {
        val dir = if (direction >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // No FLAG_SHOW_UI: we suppress the system panel and render our own instead.
        runCatching { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
        val changed = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != before
        val slider = mediaSlider
        if (root != null && !dismissing && slider != null) {
            // Already visible: reflect the new level on the existing slider and keep the panel alive,
            // without re-rendering. Rebuilding on every key press makes the panel flicker and, in the
            // Sound sheet, resets the scroll position and re-queries the app list.
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentStep = (slider.level * max).roundToInt().coerceIn(0, max)
            val nextStep = (currentStep + if (direction >= 0) 1 else -1).coerceIn(0, max)
            slider.level = nextStep / max.toFloat()
            // Reconcile with the system-reported level after the write to stay correct if the platform
            // clamps or ignores a step change.
            slider.post { slider.level = streamLevel(AudioManager.STREAM_MUSIC) }
            // The volume keys drive Media, so re-select it (Android 7–8): if the user had selected Ring
            // or an app row, a key press hands the highlight back to Media. No-op on other skins.
            if (generic78OrderedRows.isNotEmpty()) selectGenericRow(mediaSlider)
            armAutoHide()
        } else {
            show()
        }
        return changed
    }

    fun show() {
        // In live-edit the component being edited decides whether the expanded sheet is drawn (the
        // output picker also draws the sheet, then layers its modal on top). A version with no separate
        // expanded panel (Android 7–8) instead shows its single panel fully expanded, so every row is
        // visible to edit (its expand chevron is inert in the editor).
        liveEdit?.let {
            expanded = !it.version.hasExpanded ||
                it.component == PanelComponent.EXPANDED || it.component == PanelComponent.OUTPUT
        }
        // Re-read the skin (and current light/dark + customization) each time so changes apply next.
        style = buildStyle()
        render()
        if (liveEdit?.component == PanelComponent.OUTPUT) openOutputPicker()
    }

    /**
     * Live-edit: switch which component is being positioned (Android 9+ main panel ⇄ expanded sheet)
     * and redraw the real panel for it. No-op outside live-edit.
     */
    fun setLiveComponent(component: PanelComponent) {
        val config = liveEdit ?: return
        config.component = component
        // The output picker layers over the expanded sheet, so editing it draws the sheet expanded and
        // then opens the picker on top (its own colours applied); Main/Expanded draw their panel alone.
        // A version without a separate expanded panel (Android 7–8) always shows fully expanded.
        expanded = !config.version.hasExpanded ||
            component == PanelComponent.EXPANDED || component == PanelComponent.OUTPUT
        // render() rebuilds the window the picker lives inside, taking the old overlay with it — drop
        // the stale references first so a later open/close doesn't touch a detached view.
        pickerOverlay = null
        outputPicker = false
        savedSheetMediaSlider = null
        // Force a rebuild: the collapsed and expanded panels are different view trees.
        currentSig = null
        render()
        if (component == PanelComponent.OUTPUT) openOutputPicker()
        config.onComponentChanged?.invoke(component)
    }

    /**
     * Live-edit: re-apply the current component's offset by repositioning the panel's (WRAP_CONTENT)
     * window, without rebuilding it, so the drag is smooth. The window's gravity/x/y already encode
     * the docked spot + offset (see [liveEditParams]).
     */
    fun applyLiveOffset() {
        val config = liveEdit ?: return
        val view = root ?: return
        runCatching { windowManager.updateViewLayout(view, liveEditParams()) }
        reportPanelBounds()
        // Let the floating bar's X/Y fields track the panel as it's dragged/reset.
        config.onOffsetChanged?.invoke(config.offset())
    }

    /**
     * Live-edit (COLOR): repaint the panel with the current working colours. Rebuilds the panel's
     * *content* in place — the WindowManager window itself is kept, not torn down and re-added — so a
     * recolour is instant, doesn't flicker, and (crucially) doesn't re-stack the panel window above
     * the floating control bar. The style is re-derived from [currentCustomization] each pass.
     */
    fun applyLiveColors() {
        if (liveEdit == null) return
        style = buildStyle()
        val container = panel ?: return
        // Rebuild the same container's children with the new style; keeps the existing window/z-order.
        val rebuilt = makeWindowContainer()
        disableForceDark(rebuilt)
        (container.parent as? ViewGroup)?.let { parentView ->
            val lp = container.layoutParams
            val idx = parentView.indexOfChild(container)
            parentView.removeView(container)
            parentView.addView(rebuilt, idx, lp)
            panel = rebuilt
            populate(rebuilt)
        }
        // Editing the output surface: the picker layers over the sheet as a separate overlay, so rebuild
        // its card in place too (no fade — this is a live recolour, not an open/close) so its own colours
        // update alongside the sheet's.
        rebuildPickerCardInPlace()
        hideSheetBehindOutputEditor()
    }

    /** While editing the Media-output picker, hide the expanded sheet behind it so only the picker (the
     *  surface being edited) shows; otherwise keep the sheet visible. */
    private fun hideSheetBehindOutputEditor() {
        panel?.visibility =
            if (isEditor() && liveEdit?.component == PanelComponent.OUTPUT) View.GONE else View.VISIBLE
    }

    /** Swap the open picker overlay's card for a freshly-built one carrying the current colours, without
     *  the open/close animation. No-op when the picker isn't showing. */
    private fun rebuildPickerCardInPlace() {
        val overlay = pickerOverlay ?: return
        val oldCard = overlay.getChildAt(1) ?: return
        val lp = oldCard.layoutParams
        val newCard = buildOutputPickerCard()
        disableForceDark(newCard)
        overlay.removeView(oldCard)
        overlay.addView(newCard, 1, lp)
    }

    /**
     * Render (or re-render) the embedded editor preview: build the current version's chosen component
     * into the preview host with the in-progress customization, at synthetic demo state. Safe to call
     * repeatedly — the editor calls it whenever the version/component/orientation/customization change.
     */
    fun showPreview() {
        val config = preview ?: return
        // The output picker preview draws the expanded sheet, then layers its modal on top. A version
        // without a separate expanded panel (Android 7–8) shows its single panel fully expanded so
        // every row is visible in the preview.
        expanded = !config.version.hasExpanded ||
            config.component == PanelComponent.EXPANDED || config.component == PanelComponent.OUTPUT
        outputPicker = false
        ringerMenuOpen = false
        render()
        if (config.component == PanelComponent.OUTPUT) openOutputPicker()
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        stopAppSync()
        loadJob?.cancel()
        panel?.animate()?.cancel()
        if (preview != null) {
            preview.host.removeAllViews()
        } else {
            root?.let { view -> runCatching { windowManager.removeView(view) } }
        }
        root = null
        panel = null
        mediaSlider = null
        ringerIcon = null
        ringerButtons = null
        // A fresh panel starts from the device's real ringer mode again.
        requestedRingerMode = null
        pendingSilent = false
        pickerOverlay = null
        notifSlider = null
        notifSliderRow = null
        notifDisabledRow = null
        savedSheetMediaSlider = null
        notifSlider = null
        notifDisabledRow = null
        genericRingSlider = null
        alarmsOnlyRow = null
        genericToggle = null
        generic78OrderedRows = emptyList()
        generic78ExtraRows = emptyList()
        generic78AppsBox = null
        generic78Selected = null
        currentSig = null
        expanded = false // stock panel reopens collapsed
        outputPicker = false
        ringerMenuOpen = false
        dismissing = false
    }

    fun destroy() {
        hide()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── window lifecycle ──────────────────────────────────────────────────────────────────────────

    /** (Re)create the window if its signature changed, then (re)populate its content. */
    @SuppressLint("ClickableViewAccessibility")
    private fun render(forceEntrance: Boolean = false) {
        // The active component (collapsed vs expanded) can change between renders, and each carries its
        // own colour overrides, so rebuild the style every pass.
        style = buildStyle()
        if (preview != null) { renderPreview(); return }
        val sig = "${curSkin()}|${isDark()}|$expanded"
        // A fresh appearance (nothing on screen yet), or an explicit expand into the "Sound" sheet,
        // earns the slide-in entrance; a plain re-show from a repeated key press keeps the panel put.
        val entering = root == null || forceEntrance
        if (root == null || sig != currentSig) {
            root?.let { runCatching { windowManager.removeView(it) } }
            val panelView = makeWindowContainer()
            if (liveEdit != null) {
                // Live-edit uses a WRAP_CONTENT window sized to the panel and positioned at its docked
                // spot, so everything off the panel is not part of the window and the screen behind
                // stays fully interactive. The DragScrim wraps the panel and turns a drag into a
                // reposition (grabbable anywhere, even over a slider).
                val scrim = DragScrim(context).apply {
                    addView(panelView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    ))
                }
                runCatching { windowManager.addView(scrim, liveEditParams()) }
                    .onSuccess { root = scrim; panel = panelView; currentSig = sig }
                    .onFailure { root = null; panel = null; currentSig = null }
            } else {
                // Swallow taps on the panel's own empty areas so only genuinely-outside taps (which
                // fall through to the full-screen touch-modal scrim) dismiss the overlay.
                panelView.setOnTouchListener { _, _ -> true }
                val scrim = FrameLayout(context).apply {
                    setOnTouchListener { _, _ -> dismissWithAnimation(); true }
                    addView(panelView, panelPlacement())
                }
                runCatching { windowManager.addView(scrim, makeParams()) }
                    .onSuccess { root = scrim; panel = panelView; currentSig = sig }
                    .onFailure { root = null; panel = null; currentSig = null }
            }
            root?.let { disableForceDark(it) }
        }
        // A re-show (e.g. another key press) cancels any in-flight dismissal and restores position.
        panel?.let {
            it.animate().cancel()
            it.translationX = 0f; it.translationY = 0f; it.alpha = 1f
        }
        dismissing = false
        panel?.let { populate(it) }
        hideSheetBehindOutputEditor()
        if (entering && liveEdit == null) panel?.let { animateEntrance(it) }
        armAutoHide()
        if (liveEdit != null) reportPanelBounds()
    }

    /**
     * Live-edit scrim: the panel's drag surface. Only touches on the panel reach it (the window is
     * FLAG_NOT_TOUCH_MODAL, so off-panel touches fall through to the screen behind). It intercepts
     * every such touch — even ones landing on a demo slider — so the panel can be grabbed anywhere and
     * dragged. A drag converts the finger's total travel (px) into a dp offset (÷ density·scale),
     * clamps it, writes it into the working customization for the active component + orientation, and
     * re-applies it via [applyLiveOffset] (margins only, no rebuild) so the panel tracks the finger 1:1.
     */
    /**
     * The live-edit window is a WRAP_CONTENT window sized to the panel and positioned at its true
     * docked spot — so, unlike a full-screen scrim, everything outside the panel is simply not part of
     * this window and stays fully interactive (the screen behind is untouched). This wrapper holds the
     * panel, intercepts every touch within its (panel-sized) bounds — even ones on a demo slider — and
     * turns a drag into a reposition: the finger's travel updates the working offset for the active
     * component + orientation, and [applyLiveOffset] moves the whole window to match, 1:1.
     */
    private inner class DragScrim(context: Context) : FrameLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startDx = 0f
        private var startDy = 0f
        private var dragging = false
        private val perDp = density * OVERLAY_SCALE
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        private val positionMode get() = liveEdit?.mode == LiveEditMode.POSITION

        // POSITION mode: let the panel's own controls handle a tap, but once the finger travels past
        // the touch slop take over as a drag-to-move. COLOR mode never intercepts — every touch goes
        // to the panel's controls (fully interactive), and a tap is separately picked up as an element
        // selection in dispatchTouchEvent.
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!positionMode) return false
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    val off = liveEdit?.offset() ?: PanelOffset()
                    startDx = off.dxDp; startDy = off.dyDp
                    dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (abs(ev.rawX - startRawX) > slop || abs(ev.rawY - startRawY) > slop)
                    ) {
                        dragging = true
                        return true // from here the gesture is a move; children get ACTION_CANCEL
                    }
                }
            }
            return false
        }

        // COLOR mode: note where a press lands and, if it stays a tap (no drag), select the element
        // under the finger for recolouring — while still letting the control (slider/button) work.
        private var colorDownX = 0f
        private var colorDownY = 0f
        private var colorMoved = false

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (liveEdit?.mode == LiveEditMode.COLOR) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        colorDownX = ev.x; colorDownY = ev.y; colorMoved = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (abs(ev.x - colorDownX) > slop || abs(ev.y - colorDownY) > slop) colorMoved = true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!colorMoved) {
                            // When the output picker is open (editing the OUTPUT surface) it layers over
                            // the sheet as a sibling of the panel. Resolve the tap against the picker's
                            // *card* (child 1, above the dim scrim at child 0) so a tap on the dim
                            // resolves to nothing; otherwise resolve against the panel itself.
                            val card = pickerOverlay?.getChildAt(1)
                            val target = if (card != null)
                                colorInView(card, ev.x - card.left, ev.y - card.top)
                            else panel?.let { colorInView(it, ev.x - it.left, ev.y - it.top) }
                            target?.let { liveEdit?.onColorTapped?.invoke(it) }
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val config = liveEdit ?: return false
            // COLOR mode: nothing to drag, but consume so the tap's full down→up reaches
            // dispatchTouchEvent (which resolves the tapped element) even over empty panel area.
            if (!positionMode) return true
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Only reached when no child consumed the down (a tap on empty panel area).
                    startRawX = event.rawX; startRawY = event.rawY
                    val off = config.offset()
                    startDx = off.dxDp; startDy = off.dyDp
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    dragging = true
                    // Free movement: the panel can go anywhere on the display; the offset is only
                    // clamped so a sliver stays on-screen (never dragged fully out of reach).
                    val nx = (startDx + (event.rawX - startRawX) / perDp).coerceIn(-maxDx(), maxDx())
                    val ny = (startDy + (event.rawY - startRawY) / perDp).coerceIn(-maxDy(), maxDy())
                    config.withOffset(PanelOffset(nx, ny))
                    applyLiveOffset()
                }
            }
            return true
        }

        /** Horizontal offset limit (dp): the panel may travel almost the full screen width in either
         *  direction, stopping only when about [KEEP_ON_SCREEN_DP] of it would remain on-screen, so
         *  it's always grabbable but otherwise free to move anywhere. */
        private fun maxDx(): Float {
            val screen = context.resources.displayMetrics.widthPixels / perDp
            return (screen - KEEP_ON_SCREEN_DP).coerceAtLeast(KEEP_ON_SCREEN_DP)
        }

        private fun maxDy(): Float {
            val screen = context.resources.displayMetrics.heightPixels / perDp
            return (screen - KEEP_ON_SCREEN_DP).coerceAtLeast(KEEP_ON_SCREEN_DP)
        }
    }

    /** Live-edit: report the panel's current on-screen rectangle so the control bar can avoid it. */
    private fun reportPanelBounds() {
        val config = liveEdit ?: return
        val view = panel ?: return
        val cb = config.onPanelBounds ?: return
        view.post {
            val r = android.graphics.Rect()
            if (view.getGlobalVisibleRect(r)) cb(r)
        }
    }

    /**
     * Render the current panel into the editor's preview host instead of the WindowManager: the same
     * container built by [makeWindowContainer]/[populate], docked and offset by [panelPlacement], with
     * no scrim, no slide-in and no auto-hide. Swallows touches so the panel stays a pure visual while
     * the editor's own surface handles drag-to-move; re-render just rebuilds the host's single child.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun renderPreview() {
        val host = preview?.host ?: return
        host.removeAllViews()
        val panelView = makeWindowContainer()
        panelView.setOnTouchListener { _, _ -> true } // non-interactive preview
        host.addView(panelView, panelPlacement())
        disableForceDark(host)
        root = host
        panel = panelView
        dismissing = false
        populate(panelView)
    }

    /**
     * Colour editor: resolve a tap at ([x], [y]) — in the **panel's own** coordinates — to the overlay
     * element under the finger, so the user can pick what to recolour by pointing at it (no colour
     * names needed).
     *
     * A view (or an ancestor up to the panel) may carry an explicit [EditableColor] tag — set by
     * [tagColor] on elements the generic heuristic can't tell apart (the three-dot overflow button, the
     * DONE pill's fill vs. its label, the "Audio will play on" card) — and that wins. Otherwise the
     * deepest hit view decides generically: a slider → the progress/fill, an icon → the icons, text →
     * the text colour, anything else (the padded container) → the background. Returns null if nothing is
     * hit (e.g. a tap in the transparent margin).
     */
    fun previewColorAt(x: Float, y: Float): EditableColor? {
        val panelView = panel ?: return null
        return colorInView(panelView, x, y)
    }

    /** Resolve a tap at ([x],[y]) — in [root]'s own coordinates — to an [EditableColor], honouring an
     *  explicit [tagColor] on the hit view or any ancestor up to [root], else the generic heuristic.
     *  Works for any subtree (the docked panel, or the output-picker overlay layered over it). */
    private fun colorInView(root: View, x: Float, y: Float): EditableColor? {
        val hit = deepestHit(root, x, y) ?: return null
        taggedColor(hit, root)?.let { return it }
        return when {
            hit is VolumeSlider -> EditableColor.PROGRESS
            hit is TextView -> EditableColor.TEXT
            // An icon sitting on its own coloured surface (the tune/expand button, the output/connect
            // cards) → the secondary surface; a bare icon → the icon tint.
            hit is ImageView -> if (hit.background != null) EditableColor.SECONDARY else EditableColor.ICON
            else -> EditableColor.BACKGROUND
        }
    }

    /** Walk from [view] up to (and including) [top], returning the first explicit [tagColor], or null. */
    private fun taggedColor(view: View, top: View): EditableColor? {
        var v: View? = view
        while (v != null) {
            (v.getTag(R.id.tag_editable_color) as? EditableColor)?.let { return it }
            if (v === top) break
            v = v.parent as? View
        }
        return null
    }

    /** Tag [view] with the [EditableColor] a tap on it should select in the colour editor. No-op unless
     *  a colour editor is active, so the real overlay carries no extra tags. */
    private fun <T : View> T.tagColor(color: EditableColor): T = apply {
        if (liveEdit != null || preview != null) setTag(R.id.tag_editable_color, color)
    }

    /** The deepest view containing the point ([x],[y]) in [view]'s coordinate space, or [view] itself. */
    private fun deepestHit(view: View, x: Float, y: Float): View? {
        if (x < 0 || y < 0 || x > view.width || y > view.height) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val hit = deepestHit(child, x - child.left, y - child.top)
                if (hit != null) return hit
            }
        }
        return view
    }

    /**
     * The off-screen translation the panel slides to when dismissed — always *toward* the edge it's
     * docked against: the bottom sheet drops down, the top panel lifts up, the right-edge panels slide
     * right. The entrance ([animateEntrance]) starts from this same offset, so the panel slides in from
     * its anchored edge and, on dismiss, retreats back out the same way.
     */
    private fun exitOffset(view: View): PointF = when {
        isRichPanel() && expanded -> PointF(0f, view.height.toFloat())          // bottom → down
        isRichPanel() -> PointF(view.width.toFloat(), 0f)                        // right → right
        style.placement == Placement.TOP -> PointF(0f, -view.height.toFloat())  // top → up
        else -> PointF(view.width.toFloat(), 0f)                                 // right → right
    }

    /**
     * Slide the freshly-shown panel in from its anchored edge — a right-edge panel arrives from the
     * right, a top panel drops down from the top, a bottom sheet rises up from the bottom — fading in
     * as it settles. Deferred to a pre-draw pass so the panel's measured width/height are known for the
     * starting offset, and applied before the first frame so it never flashes at its resting spot.
     */
    private fun animateEntrance(view: View) {
        view.alpha = 0f
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                // The panel can be torn down or replaced before this first frame (a fast re-show or an
                // outside tap); only animate the view that's still the one on screen.
                if (view !== panel || dismissing) { view.alpha = 1f; return true }
                val start = exitOffset(view)
                view.translationX = start.x
                view.translationY = start.y
                view.animate()
                    .translationX(0f).translationY(0f).alpha(1f)
                    .setInterpolator(enterInterpolator)
                    .setDuration(SLIDE_IN_MS)
                    .start()
                return true
            }
        })
    }

    /** Slide the panel off whichever edge it's docked on, then remove the window. */
    private fun dismissWithAnimation() {
        val view = panel
        if (view == null || dismissing) { if (view == null) hide(); return }
        dismissing = true
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(appsSyncRunnable)
        loadJob?.cancel()
        val exit = exitOffset(view)
        view.animate()
            .translationX(exit.x).translationY(exit.y).alpha(0f)
            .setInterpolator(exitInterpolator)
            .setDuration(SLIDE_OUT_MS)
            .withEndAction { hide() }
            .start()
    }

    private fun armAutoHide() {
        handler.removeCallbacks(hideRunnable)
        // The editor preview and the on-screen position editor are persistent — they never time out.
        if (preview != null || liveEdit != null) return
        // The expanded "Sound" sheet (Android 9–11 and 12) never times out — it stays until the user
        // taps DONE or outside; only the compact panels auto-hide.
        if (isRichPanel() && (expanded || outputPicker)) return
        // The Android 7–8 panel lingers longer (~6 s) before dismissing itself.
        val delay = if (curSkin() == OverlaySkin.ANDROID_7_8) AUTO_HIDE_78_MS else AUTO_HIDE_MS
        handler.postDelayed(hideRunnable, delay)
    }

    /**
     * The bespoke two-state renderer (collapsed right-edge panel ⇄ bottom "Sound" sheet) backs both
     * Android 9–11 and the Material You family (Android 12 / 13–14); they differ only by [OverlayStyle]
     * (shapes/colours, filled-pill vs line-thumb slider, ringer cycle vs menu). Android 7–8 uses the
     * generic list renderer.
     */
    private fun isRichPanel(): Boolean = when (curSkin()) {
        OverlaySkin.ANDROID_9_11, OverlaySkin.MATERIAL_YOU, OverlaySkin.ANDROID_13_14 -> true
        else -> false
    }

    /**
     * Only Android 13–14 uses the newer "Sound & vibration" sheet — no divider lines, airier rows and
     * rounded (pill) SETTINGS/DONE buttons. Android 12 and Android 9–11 keep the older "Sound" sheet
     * with row dividers and plain "SEE MORE"/"DONE" text buttons.
     */
    private fun isModernSheet() = curSkin() == OverlaySkin.ANDROID_13_14

    /**
     * The Android 14+ icon set (offered by the Android 13–15 skin as "Android 14" or "Android 15"):
     * the main panel's ringer button is a megaphone/speaker (crossed out when silenced) and the Sound
     * sheet splits Ring (phone icon) from Notification (bell). Android 13 keeps the classic bell + a
     * single combined control.
     */
    private fun isAndroid14IconSet() =
        curSkin() == OverlaySkin.ANDROID_13_14 && curIconSet() != IconSet.ANDROID_13

    /**
     * The Android 15 media-output sheet (offered by the Android 13–15 skin): a redesigned "Sound &
     * vibration" sheet with an "Audio will play on" output selector and filled-pill sliders, plus a
     * media-output picker that shows the volume as a percentage while dragging. The collapsed panel is
     * identical to Android 14.
     */
    private fun isAndroid15Sheet() =
        curSkin() == OverlaySkin.ANDROID_13_14 && curIconSet() == IconSet.ANDROID_15

    private fun hideSecondaryIconsLegacy910() =
        curSkin() == OverlaySkin.ANDROID_9_11 &&
            (legacyVersion() == LegacyVersion.ANDROID_9 || legacyVersion() == LegacyVersion.ANDROID_10)

    /** The selected Android 9–11 sub-version; only consulted for the ANDROID_9_11 skin. */
    private fun legacyVersion() = editVersion()?.legacyVersion ?: prefs.getLegacyVersion()

    private fun isLegacy9() =
        curSkin() == OverlaySkin.ANDROID_9_11 && legacyVersion() == LegacyVersion.ANDROID_9

    /**
     * Android 9 drew the media stream as the Pie music note (hollow head; the muted variant slashes
     * it, leaving the flag detached above the cut). Android 10 switched to the filled-head note that
     * every later version — and so every other skin here — uses.
     */
    private fun mediaIconRes() =
        if (isLegacy9()) R.drawable.ic_stream_media_outline else R.drawable.ic_stream_media

    private fun mediaMutedIconRes() =
        if (isLegacy9()) R.drawable.ic_stream_media_off_9 else R.drawable.ic_stream_media_off

    private fun makeWindowContainer(): ViewGroup = when {
        isRichPanel() && expanded -> sheetContainer()
        isRichPanel() -> collapsedColumn()
        else -> genericContainer()
    }

    /**
     * The scrim window: fills the content area, transparent, non-focusable (so keys/back still reach
     * the app) and touch-modal (no FLAG_NOT_TOUCH_MODAL) so every touch lands in it — off-panel taps
     * are caught by the scrim and never leak to whatever is behind.
     *
     * The full-screen skins (Android 7–8's top sheet and the rich collapsed right-edge control) add
     * IN_SCREEN/NO_LIMITS and pin to the true screen top so they can span the status-bar region. The
     * expanded "Sound" sheet keeps the default confined window (no such flags) so it sits within the
     * safe area — footer visible, not overlapping the status/navigation bars.
     */
    private fun makeParams(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Android 7–8 docks flush to the very top, drawing *over* the status bar (clock / battery)
        // like the real panel; NO_LIMITS lets the window extend into that region.
        //
        // The rich skins' *collapsed* right-edge control (Android 9–15) also spans the full display,
        // so its Gravity.CENTER_VERTICAL lands on the true screen centre instead of the centre of the
        // status-bar-inset content area (which sits visibly low). Their *expanded* bottom sheet keeps
        // the confined window so its footer still clears the status/navigation bars.
        val richCollapsed = isRichPanel() && !expanded
        val fullScreen = curSkin() == OverlaySkin.ANDROID_7_8 || richCollapsed
        if (fullScreen) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            // Both full-screen cases must measure against the *whole* physical display — including the
            // top status bar (clock / Wi-Fi / battery) — not the MATCH_PARENT area, which some devices
            // still shrink to exclude that bar even under NO_LIMITS. Left as MATCH_PARENT the scrim (and
            // whatever docks against its edge) can sit below the status bar rather than at the true top:
            //  - Android 7–8's top sheet would leave a status-bar-height gap instead of drawing flush
            //    over the bar like the real panel;
            //  - the rich collapsed control's Gravity.CENTER_VERTICAL would centre on the inset area and
            //    sit visibly low.
            // Pinning the scrim to y = 0 at the real screen height fixes both. The expanded bottom sheet
            // is *not* full-screen, so it stays MATCH_PARENT and its footer still clears the system bars.
            if (fullScreen) realScreenHeight() else WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (fullScreen) {
                gravity = Gravity.TOP
                y = 0
            }
        }
    }

    /** Combined height (px) of the status + navigation bars — the vertical space a *confined* window
     *  (the expanded bottom sheet) loses to the system bars. Used to keep the sheet's title/footer
     *  from being pushed under a bar (or off-screen) on tall sheets. Falls back to a sane estimate on
     *  APIs / states without live insets. */
    private fun systemBarsHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.systemBars())
            val total = insets.top + insets.bottom
            if (total > 0) return total
        }
        // Pre-R (or before insets resolve): estimate status (~24dp) + nav (~48dp).
        return dp(72)
    }

    /** The full physical display height in px, including the status and navigation bar regions. */
    private fun realScreenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        }

    /**
     * Window params for the live-edit panel: a WRAP_CONTENT (or full-width, for the sheets) window
     * pinned to the panel's docked edge, then nudged by the working offset ([activeOffset], +x = right,
     * +y = down). Because the window is only as big as the panel, everything off it stays interactive —
     * the panel really is floating on top of the current screen, exactly where it will dock. Re-derived
     * (and re-applied) on every drag step by [applyLiveOffset].
     */
    private fun liveEditParams(): WindowManager.LayoutParams {
        val off = activeOffset()
        val dx = dp(off.dxDp)
        val dy = dp(off.dyDp)
        val edge = dp(style.edgeMarginDp)
        // The sheets want the full display width; the compact panels wrap to their content.
        val expandedSheet = isRichPanel() && expanded
        val fullWidth = expandedSheet ||
            (style.placement == Placement.TOP && style.stretch)
        // The collapsed right-edge control and the Android 7–8 top sheet span the whole display, so
        // they keep IN_SCREEN/NO_LIMITS (edge-centre on the true screen centre, drag freely past the
        // edges). The expanded bottom sheet is *confined* to the safe area — exactly like the live
        // overlay's window (see [makeParams]) — so a tall sheet is bounded by the screen and its top
        // (the "Volume"/"Sound" title) can never slide off the top edge and become invisible.
        val flags = if (expandedSheet)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return WindowManager.LayoutParams(
            if (fullWidth) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            when {
                isRichPanel() && expanded -> {
                    // Bottom sheet: docked to the bottom edge, shifted by the offset.
                    gravity = Gravity.BOTTOM
                    x = dx
                    y = -dy
                }
                isRichPanel() -> {
                    // Right-edge collapsed control, vertically centred.
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    x = edge - dx
                    y = dy
                }
                style.placement == Placement.TOP -> {
                    // Android 7–8 top sheet.
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = dx
                    y = edge + dy
                }
                else -> {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    x = edge - dx
                    y = dy
                }
            }
        }
    }

    /**
     * Where the panel sits inside the full-screen scrim (or the editor's preview host): the docking
     * gravity + edge margin, plus the user's per-component/per-orientation position offset
     * ([activeOffset], +x = right, +y = down). Offsets are baked into the layout margins (not view
     * translation) so they coexist cleanly with the slide-in entrance, which animates translation.
     */
    private fun panelPlacement(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val off = activeOffset()
        val dx = dp(off.dxDp)
        val dy = dp(off.dyDp)
        val edge = dp(style.edgeMarginDp)
        when {
            isRichPanel() && expanded -> {
                // Full-width bottom sheet: shift it right by dx (keeping full width) and down by dy.
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.BOTTOM
                lp.leftMargin = dx
                lp.rightMargin = -dx
                lp.bottomMargin = -dy
            }

            isRichPanel() -> {
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.rightMargin = edge - dx
                lp.topMargin = dy
            }

            style.placement == Placement.TOP -> {
                if (style.stretch) lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.leftMargin = dx
                lp.topMargin = edge + dy
            }

            else -> {
                lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                lp.rightMargin = edge - dx
                lp.topMargin = dy
            }
        }
        return lp
    }

    private fun populate(container: ViewGroup) {
        mediaSlider = null // each path re-captures the live media slider it builds
        ringerIcon = null
        ringerButtons = null
        genericRingSlider = null
        alarmsOnlyRow = null
        // Drop any previous app box/timer; the renderers that show apps re-arm it via startAppSync,
        // and the collapsed panels (which show none) then correctly leave it stopped.
        stopAppSync()
        when {
            isRichPanel() && expanded && isAndroid15Sheet() -> populateAndroid15Sheet(container as LinearLayout)
            isRichPanel() && expanded -> populate911Expanded(container as LinearLayout)
            isRichPanel() -> populate911Collapsed(container as LinearLayout)
            else -> populateGeneric(container as FrameLayout)
        }
    }

    // ── generic renderer (Material You, Android 7–8) ──────────────────────────────────────────────

    /**
     * The Android 7–8 panel: a padded, backgrounded [FrameLayout] holding the row stack, so Android
     * 7's [GenericChevronToggle] can float on top of it (over the trailing space every row already
     * reserves for a chevron — see [makeSlider]'s `reserveTrailing`) instead of taking its own row
     * and adding visible height above Media.
     */
    private fun genericContainer(): FrameLayout {
        val stackHorizontally = style.orientation == SliderOrientation.VERTICAL
        val content = LinearLayout(context).apply {
            orientation = if (stackHorizontally) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Animate rows appearing/disappearing (and the panel growing/shrinking to fit) so the
            // Android 7–8 expand/collapse is a smooth slide rather than a hard swap.
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(190)
            }
        }
        return FrameLayout(context).apply {
            val pad = dp(style.containerPaddingDp)
            setPadding(pad, pad, pad, pad)
            background = roundedBg(style.containerColor, dp(style.containerCornerDp).toFloat())
            addView(
                content,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
            )
        }
    }

    /** The selected Android 7–8 sub-version; only consulted for the ANDROID_7_8 skin. */
    private fun isSeven() = curSevenEight() == SevenEightVersion.ANDROID_7

    private fun populateGeneric(container: FrameLayout) {
        val content = container.getChildAt(0) as LinearLayout
        loadJob?.cancel()
        content.removeAllViews()
        // The toggle floats directly on the outer container (not content), so content.removeAllViews()
        // above doesn't reach it — drop the previous one explicitly before adding a fresh one.
        genericToggle?.let { container.removeView(it) }

        // Android 8's expanded order is Media / Ring / Alarm — Media is already first, so its own
        // chevron can carry the toggle and never move. Android 7's expanded order is Ring / Media /
        // Alarm, so neither of those rows can own the toggle without visually jumping once the other
        // shows/hides around it; instead it floats over the trailing space every row already reserves
        // for a chevron (see [makeSlider]'s `reserveTrailing`), so it adds no extra height of its own.
        // Media (icon-only, no chevron of its own) is what stays visible collapsed since that's the
        // row the hardware volume keys drive.
        val seven = isSeven()
        val toggle = if (seven) GenericChevronToggle().also { it.dir = if (expanded) ChevronDir.UP else ChevronDir.DOWN } else null
        genericToggle = toggle

        val media = streamSlider(
            AudioManager.STREAM_MUSIC,
            tintedIcon(R.drawable.ic_stream_media),
            null,
            if (seven) null else "Media",
            if (seven) ChevronDir.NONE else (if (expanded) ChevronDir.UP else ChevronDir.DOWN),
            // The Nougat/Oreo muted note (the glyph is cut along the slash), not the modern overlay.
            mutedIcon = tintedIcon(R.drawable.ic_stream_media_off_78),
        ) { toggleExpand() }
        mediaSlider = media

        val ring = streamSlider(
            AudioManager.STREAM_RING,
            tintedIcon(R.drawable.ic_stream_ring),
            null,
            if (seven) null else "Ring",
            ChevronDir.NONE,
            mutedIcon = tintedIcon(R.drawable.ic_ring_off),
            tieNotification = true,
            tieCall = true,
            afterChange = { refreshAlarmsOnlyFooter() },
        ) {}
        genericRingSlider = ring

        val alarm = streamSlider(
            AudioManager.STREAM_ALARM,
            tintedIcon(R.drawable.ic_stream_alarm),
            null,
            if (seven) null else "Alarm",
            ChevronDir.NONE,
            mutedIcon = tintedIcon(R.drawable.ic_stream_alarm_off),
        ) {}

        // Per-app sliders live in their own box so the "Alarms only" footer stays last even though
        // they load asynchronously.
        val appsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Rows added in expanded display order (Ring, Media, Alarm on Android 7); the collapsed
        // state just hides everything but Media.
        val streamRows = if (seven) listOf(ring, media, alarm) else listOf(media, ring, alarm)
        // Touching a row selects it: it stays highlighted and the rest stay greyed until another row
        // is selected (the greyed state persists past finger-lift, unlike the stock panel's momentary dim).
        streamRows.forEach { s -> s.onDragStateChange = { dragging -> if (dragging) selectGenericRow(s) } }
        streamRows.forEach { content.addView(it, pillParams(first = false)) }
        content.addView(
            appsBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        val footer = alarmsOnlyFooter()
        alarmsOnlyRow = footer
        content.addView(
            footer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        if (toggle != null) {
            // Whichever row ends up on top (Media collapsed, Ring expanded) always starts flush at
            // content's top edge and shares the same thickness, so a toggle centred in that same band
            // lines up with it either way without needing to know which row that currently is.
            val rowThickness = dp(style.pillThicknessDp)
            val toggleSize = dp(40)
            val lp = FrameLayout.LayoutParams(toggleSize, toggleSize, Gravity.TOP or Gravity.END).apply {
                topMargin = maxOf(0, (rowThickness - toggleSize) / 2)
            }
            container.addView(toggle, lp)
        }

        generic78OrderedRows = streamRows + appsBox
        generic78ExtraRows = streamRows.filter { it !== media } + appsBox
        generic78AppsBox = appsBox
        // Media is selected by default — the panel opens from the volume keys, which drive Media.
        selectGenericRow(media)
        applyGenericExpandState()
        refreshAlarmsOnlyFooter()
    }

    /**
     * Android 7's expand/collapse chevron, drawn the same way as [VolumeSlider]'s trailing chevron
     * but as its own floating view instead of on a stream slider — see [populateGeneric] for why.
     */
    private inner class GenericChevronToggle : View(context) {
        var dir: ChevronDir = ChevronDir.DOWN
            set(value) { field = value; invalidate() }

        // The same material expand_less/expand_more glyphs the Android 8 media row draws.
        private val up = tintedIcon(R.drawable.ic_expand_up)
        private val down = tintedIcon(R.drawable.ic_expand_down)

        init {
            // Play the system touch-click on expand/collapse (setOnClickListener → performClick does
            // this when sound effects are on). Android 8's chevron plays the same click from inside
            // VolumeSlider's touch handler, so both versions sound identical.
            isSoundEffectsEnabled = true
            setOnClickListener { toggleExpand() }
        }

        override fun onDraw(canvas: Canvas) {
            val glyph = (if (dir == ChevronDir.UP) up else down) ?: return
            val half = dp(12)
            glyph.setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
            glyph.draw(canvas)
        }
    }

    /**
     * Show/hide the Android 7–8 rows for the current [expanded] state (Media always visible, the rest
     * only when expanded), fix each visible row's top gap, and load/clear the per-app box. Called
     * both on build and — without a rebuild — from [toggleExpand], so the container's LayoutTransition
     * animates the change.
     */
    private fun applyGenericExpandState() {
        val vis = if (expanded) View.VISIBLE else View.GONE
        generic78ExtraRows.forEach { it.visibility = vis }
        // The first visible row sits flush; every later visible row gets the inter-row gap.
        var seenVisible = false
        generic78OrderedRows.forEach { row ->
            (row.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.topMargin = if (seenVisible) dp(style.spacingDp) else 0
                row.layoutParams = lp
            }
            if (row.visibility != View.GONE) seenVisible = true
        }
        val box = generic78AppsBox
        if (expanded) {
            if (box != null) loadPillApps(box)
        } else {
            stopAppSync()
            loadJob?.cancel()
            box?.removeAllViews()
        }
    }

    /**
     * Make [slider] the selected Android 7–8 row and grey out the rest. Unlike a transient
     * drag-highlight, the selection *sticks* after the finger lifts — every other row stays greyed
     * until a different row is selected (by touching it, or Media by the hardware volume keys).
     */
    private fun selectGenericRow(slider: VolumeSlider?) {
        generic78Selected = slider
        applyGenericSelection()
    }

    /** Dim every Android 7–8 row except [generic78Selected] to reflect the current selection. Safe to
     *  call on non-generic skins (their [generic78OrderedRows] is empty, so it's a no-op) and after
     *  the app list changes, so freshly-added app rows inherit the greyed state. */
    private fun applyGenericSelection() {
        val selected = generic78Selected
        fun apply(view: View?) {
            when (view) {
                is VolumeSlider -> view.dimmed = selected != null && view !== selected
                is ViewGroup -> for (i in 0 until view.childCount) apply(view.getChildAt(i))
            }
        }
        generic78OrderedRows.forEach { apply(it) }
    }

    /**
     * The "Alarms only" footer under the Android 7–8 rows: a divider, the DND icon with "Alarms
     * only / Until you turn off Do Not Disturb", and a TURN OFF NOW action. Shown only while the
     * ring is muted; TURN OFF NOW restores the minimum audible ring level.
     */
    private fun alarmsOnlyFooter(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            View(context).apply { setBackgroundColor(style.trackColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(12) },
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(16), dp(6), 0)
        }
        row.addView(
            ImageView(context).apply { setImageDrawable(tintedIcon(R.drawable.ic_dnd_minus)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
        )
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "Alarms only"
            setTextColor(style.textColor)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(context).apply {
            text = "Until you turn off Do Not Disturb"
            setTextColor(fade(style.textColor, 0.7f))
            textSize = 13f
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        actionRow.addView(textButton("TURN OFF NOW") { turnOffAlarmsOnly() })
        box.addView(
            actionRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        return box
    }

    /** Ring muted (silent/vibrate mode or zero ring volume) → notifications are silenced too. */
    private fun isGenericRingMuted() =
        curRingerMode() != AudioManager.RINGER_MODE_NORMAL ||
            streamLevel(AudioManager.STREAM_RING) <= 0.001f

    /** Grey out (and lock) the Ring bar and show the "Alarms only" footer while the ring is muted. */
    private fun refreshAlarmsOnlyFooter() {
        val muted = isGenericRingMuted()
        genericRingSlider?.let {
            it.sliderEnabled = !muted
            // While muted (vibrate/silent/DND) the ring stream can still report its pre-mute volume,
            // which would leave the bar showing partial fill even though nothing sounds. Snap the
            // slider all the way to the minimum so the muted state reads correctly; restore the real
            // level once it's unmuted. Visual-only — the stream volume itself is untouched here.
            it.level = if (muted) 0f else streamLevel(AudioManager.STREAM_RING)
        }
        alarmsOnlyRow?.visibility = if (muted) View.VISIBLE else View.GONE
    }

    /** TURN OFF NOW: restore the ringer and the minimum non-muted ring/notification volume so both
     *  can be adjusted normally again. */
    private fun turnOffAlarmsOnly() {
        // Editors are visual-only: TURN OFF NOW performs no action (never touches the device or even
        // the synthetic state).
        if (isEditor()) return
        runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_RING, 1, 0) }
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 1, 0) }
        genericRingSlider?.level = streamLevel(AudioManager.STREAM_RING)
        refreshAlarmsOnlyFooter()
    }

    private fun toggleExpand() {
        // Editors are visual-only: the Android 7–8 expand/collapse chevron is inert (the panel is
        // already shown fully expanded so every row is visible to edit).
        if (isEditor()) return
        // Android 7–8 keeps the same window and just toggles row visibility so the container's
        // LayoutTransition animates the expand/collapse (rebuilding would hard-swap and flicker).
        expanded = !expanded
        val dir = if (expanded) ChevronDir.UP else ChevronDir.DOWN
        // Android 7's chevron lives on its own pinned header; Android 8's lives on the Media row.
        genericToggle?.let { it.dir = dir } ?: run { mediaSlider?.chevronDir = dir }
        applyGenericExpandState()
        armAutoHide()
    }

    private fun loadPillApps(container: LinearLayout) {
        startAppSync(container) { app ->
            // Android 7 rows are icon-only, so app rows drop their name label too.
            val label = if (isSeven()) null else app.label
            makeSlider(app.icon, glyph = null, label, ChevronDir.NONE, onChevron = {}, pressedHalo = true) { level ->
                appVolume.setVolume(app.pkg, level, app.piids)
            }.apply {
                // Start at the level remembered for this playback session (full for a fresh one).
                level = appVolume.volumeFor(app.pkg)
                layoutParams = pillParams(first = false)
                onDragStateChange = { dragging -> if (dragging) selectGenericRow(this) }
            }
        }
    }

    private fun pillParams(first: Boolean): LinearLayout.LayoutParams {
        // Stretched rows fill the container's cross axis (full-width top sheet); others wrap.
        val stretchW = style.stretch && style.orientation == SliderOrientation.HORIZONTAL
        val stretchH = style.stretch && style.orientation == SliderOrientation.VERTICAL
        val w = if (stretchW) LinearLayout.LayoutParams.MATCH_PARENT
        else LinearLayout.LayoutParams.WRAP_CONTENT
        val h = if (stretchH) LinearLayout.LayoutParams.MATCH_PARENT
        else LinearLayout.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(w, h).apply {
            if (!first) {
                if (style.orientation == SliderOrientation.VERTICAL) marginStart = dp(style.spacingDp)
                else topMargin = dp(style.spacingDp)
            }
        }
    }

    private fun streamSlider(
        streamType: Int,
        icon: Drawable?,
        glyph: String?,
        label: String?,
        chevron: ChevronDir,
        mutedIcon: Drawable? = null,
        tieNotification: Boolean = false,
        tieCall: Boolean = false,
        afterChange: (() -> Unit)? = null,
        onChevron: () -> Unit,
    ) = makeSlider(icon, glyph, label, chevron, onChevron, mutedIcon = mutedIcon, pressedHalo = true) { level ->
        setStream(streamType, level, tieNotification, tieCall)
        afterChange?.invoke()
    }.apply { level = streamLevel(streamType) }

    // ── Android 9–11: collapsed right-edge panel ──────────────────────────────────────────────────

    private fun collapsedColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }

    private fun populate911Collapsed(container: LinearLayout) {
        // Material You integrates the ringer into the slider card with an expand animation.
        if (style.ringerMenu) {
            populateMaterialYouCollapsed(container)
            return
        }
        loadJob?.cancel()
        container.removeAllViews()
        // Card wide enough to hold the slider (its thickness) plus breathing room.
        val cardW = maxOf(dp(54), dp(style.pillThicknessDp) + dp(24))
        val corner = dp(style.containerCornerDp).toFloat()

        // Ringer-mode button (its own card): Android 9–11 cycles normal → vibrate → silent. The icon
        // follows the editable icon tint so recolouring "icons" visibly affects it (the real panel used
        // an accent tint here, but tying it to the icon colour makes that control meaningful). On the
        // Android 13–15 skin the "Active mode icon" override (modeIconColor), when set, wins instead.
        val ring = ImageView(context).apply {
            val p = dp(15); setPadding(p, p, p, p)
            background = roundedBg(style.containerColor, corner)
            setOnClickListener {
                if (isEditor()) return@setOnClickListener // visual-only preview
                armAutoHide() // interacting keeps the panel alive
                // Retint this one icon rather than render()-ing the whole panel: a rebuild cancels the
                // app-sync load and recreates every slider, which is what made a fast series of taps
                // lag behind the finger and land on a stale icon.
                cycleRinger(); refreshRingerViews()
            }
        }
        ringerIcon = ring
        refreshRingerViews()
        container.addView(
            ring,
            LinearLayout.LayoutParams(cardW, cardW).apply { bottomMargin = dp(style.spacingDp) },
        )

        // Slider card: vertical media slider + media icon + divider + tune footer.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(style.containerColor, corner)
        }

        val media = ImageView(context).apply {
            val p = dp(10); setPadding(p, p, p, p)
        }
        // The media note under the slider follows the icon tint, or the "Media note icon" override
        // (mediaIconColor) when the Android 13–15 skin sets one.
        val mediaTint = style.mediaIconColor ?: style.iconTint
        val refreshMediaIcon = { level: Float ->
            val iconRes = if (level <= 0.001f) mediaMutedIconRes() else mediaIconRes()
            media.setImageDrawable(tintedIcon(iconRes, mediaTint))
        }
        val slider = makeSlider(
            icon = null, glyph = null, label = null, chevron = ChevronDir.NONE, onChevron = {},
            orientation = SliderOrientation.VERTICAL, fill = true, reserveTrailing = false,
        ) { level ->
            val applied = setStream(AudioManager.STREAM_MUSIC, level)
            refreshMediaIcon(applied)
            applied
        }
            .apply { level = streamLevel(AudioManager.STREAM_MUSIC) }
        mediaSlider = slider
        refreshMediaIcon(slider.level)
        card.addView(
            slider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(155)).apply { topMargin = dp(12) },
        )

        card.addView(media, iconRowParams())

        card.addView(
            View(context).apply { setBackgroundColor(style.trackColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )

        // Android 9's panel opened the sheet with a settings-gear button; 10 and 11 use the
        // tune/sliders button. Either way it opens our own sheet — it never redirects to Settings.
        // Both glyphs are the real 16dp SystemUI assets, padded up to the usual footer height.
        val expandIcon =
            if (legacyVersion() == LegacyVersion.ANDROID_9) R.drawable.ic_settings_gear else R.drawable.ic_tune
        val tune = ImageView(context).apply {
            setImageDrawable(tintedIcon(expandIcon))
            val p = dp(16); setPadding(p, p, p, p)
            background = roundedBg(style.secondaryContainerColor, 0f, 0f, corner, corner)
            setOnClickListener { expand() }
        }
        card.addView(tune, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        container.addView(card, LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun iconRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL }

    // ── Android 9–11: expanded bottom "Sound" sheet ───────────────────────────────────────────────

    private fun sheetContainer() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val corner = dp(20).toFloat()
        background = roundedBg(style.containerColor, corner, corner, 0f, 0f)
        setPadding(0, dp(10), 0, dp(16))
    }

    private fun populate911Expanded(container: LinearLayout) {
        loadJob?.cancel()
        container.removeAllViews()
        notifSlider = null
        notifSliderRow = null
        notifDisabledRow = null

        // Android 13–14 uses the newer "Sound & vibration" title and a clean, divider-less sheet;
        // Android 12 and 9–11 keep the older "Sound" title with row dividers.
        val modern = isModernSheet()
        // Android 9 and 10 titled this sheet "Volume"; Android 11 (and 12) use "Sound", and 13–14
        // use "Sound & vibration".
        val legacyVolume = curSkin() == OverlaySkin.ANDROID_9_11 &&
            (legacyVersion() == LegacyVersion.ANDROID_9 || legacyVersion() == LegacyVersion.ANDROID_10)
        val title = TextView(context).apply {
            text = when {
                modern -> "Sound & vibration"
                legacyVolume -> "Volume"
                else -> "Sound"
            }
            // The title is independently editable (titleColor), falling back to the body text colour.
            setTextColor(style.titleColor ?: style.textColor)
            textSize = if (modern) 19f else 17f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }.tagColor(EditableColor.TITLE)
        container.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (!modern) addDivider(container)

        // The stream + per-app rows live in a height-capped scroller so a crowded sheet (e.g. in
        // landscape, where vertical space is tight) stays fully on-screen; the title and footer stay
        // pinned. When the rows fit, the scroller wraps to their height so the sheet isn't oversized.
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // The sheet rows use the outlined icon set (hollow-head note, hollow handset, outlined
        // bell) across Android 11–14; 9 and 10 hide the row icons entirely.
        addStreamRow911(rows, R.drawable.ic_stream_media_outline, "Media volume", AudioManager.STREAM_MUSIC)
        addStreamRow911(rows, R.drawable.ic_stream_call_outline, "Call volume", AudioManager.STREAM_VOICE_CALL)
        if (isModernSheet() && isAndroid14IconSet()) {
            // Android 14 draws Ring as the ringing handset; unlike Media it keeps that icon even
            // at zero — the stock sheet doesn't slash it, it just hands off to the disabled
            // Notification row below.
            addStreamRow911(
                rows,
                R.drawable.ic_stream_ring_phone,
                "Ring volume",
                AudioManager.STREAM_RING,
                afterChange = { refreshExpandedNotificationAvailability() },
            )
            val (notifRow, notif) =
                addStreamRow911(rows, R.drawable.ic_stream_notification, "Notification volume", AudioManager.STREAM_NOTIFICATION)
            val disabled = disabledNotificationRow911()
            notifSlider = notif
            notifSliderRow = notifRow
            notifDisabledRow = disabled
            rows.addView(disabled, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            refreshExpandedNotificationAvailability()
        } else {
            // The outlined bell is what both the Android 9–11 and Android 12 sheets actually drew
            // (AOSP's ic_volume_ringer is unchanged through 13).
            addStreamRow911(rows, R.drawable.ic_stream_ring_outline, "Ring & notification volume", AudioManager.STREAM_RING, tieNotification = true)
        }
        addStreamRow911(rows, R.drawable.ic_stream_alarm, "Alarm volume", AudioManager.STREAM_ALARM)

        // Per-app sliders (Volume++'s reason for existing) are appended in the same native row style.
        val appsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(appsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        load911Apps(appsBox)

        container.addView(
            cappedScroll(rows),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )

        container.addView(makeFooter())
    }

    /**
     * Wrap [content] in a scroller whose height is capped to what's left after the title/footer, so
     * an overlong sheet scrolls instead of running off-screen. Below the cap it wraps to content, so
     * a short sheet stays compact. The cap is derived from the current display height (which already
     * reflects portrait vs. landscape).
     */
    private fun cappedScroll(content: View): ScrollView {
        // Reserve room for the pinned title + footer (and a little slack) so they're never clipped.
        // The expanded sheet's window is confined to the safe area (it excludes the status/navigation
        // bars), so also subtract those insets — otherwise a tall sheet would push its title up under
        // the status bar (or, in the live-edit window, right off the top edge) and hide it.
        val bars = systemBarsHeight()
        val maxH = (context.resources.displayMetrics.heightPixels - bars - dp(200)).coerceAtLeast(dp(140))
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return scroll
    }

    private fun addStreamRow911(
        container: LinearLayout,
        iconRes: Int,
        label: String,
        streamType: Int,
        mutedIconRes: Int? = null,
        tieNotification: Boolean = false,
        afterChange: (() -> Unit)? = null,
    ): Pair<View, VolumeSlider> {
        val icon = ImageView(context)
        val refreshIcon = { level: Float ->
            val targetIcon = if (mutedIconRes != null && level <= 0.001f) mutedIconRes else iconRes
            icon.setImageDrawable(tintedIcon(targetIcon))
        }
        val slider = makeSlider(
            icon = null, glyph = null, label = null, chevron = ChevronDir.NONE, onChevron = {},
            orientation = SliderOrientation.HORIZONTAL, fill = true, reserveTrailing = false,
            render = SliderRender.LINE_THUMB,
        ) { level ->
            val applied = setStream(streamType, level, tieNotification)
            refreshIcon(applied)
            afterChange?.invoke()
            applied
        }
            .apply { level = streamLevel(streamType) }
        refreshIcon(slider.level)
        if (streamType == AudioManager.STREAM_MUSIC) mediaSlider = slider
        val row = row911(icon, label, slider)
        container.addView(row)
        if (!isModernSheet()) addDivider(container)
        return row to slider
    }

    private fun load911Apps(container: LinearLayout) {
        startAppSync(container) { app ->
            val slider = makeSlider(
                icon = null, glyph = null, label = null, chevron = ChevronDir.NONE, onChevron = {},
                orientation = SliderOrientation.HORIZONTAL, fill = true, reserveTrailing = false,
                render = SliderRender.LINE_THUMB,
            ) { level -> appVolume.setVolume(app.pkg, level, app.piids) }
                .apply { level = appVolume.volumeFor(app.pkg) }
            val icon = ImageView(context).apply { setImageDrawable(app.icon) }
            val row = row911(icon, app.label, slider)
            // Wrap row (+ divider on the older sheets) so removing a stopped app takes its divider too.
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(row)
                if (!isModernSheet()) addDivider(this)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
        }
    }

    /** A Sound-sheet row: leading icon + (label stacked over a horizontal slider). */
    private fun row911(icon: ImageView, label: String, slider: View): View {
        val hideIcon = hideSecondaryIconsLegacy910()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Without dividers (Android 13–14) give each row more vertical breathing room.
            val h = dp(16); val v = if (isModernSheet()) dp(14) else dp(8); setPadding(h, v, h, v)
        }
        if (!hideIcon) {
            row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        }

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(context).apply {
            text = label
            setTextColor(style.textColor)
            textSize = 14f
            // Indent by the slider's track inset so the label's left edge lines up with where the
            // track line actually starts, instead of sitting a thumb-radius to its left.
            setPadding(dp(9), 0, 0, 0)
        }
        col.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        col.addView(slider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun makeFooter(): View {
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
        }
        if (isModernSheet()) {
            // Android 13–14: rounded (pill) buttons — SETTINGS outlined, DONE filled tonal.
            footer.addView(outlinedPillButton("Settings") { openSoundSettings() })
            footer.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            footer.addView(filledPillButton("Done") { dismissSheet() })
        } else {
            footer.addView(textButton("SEE MORE") { openSoundSettings() })
            footer.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
            footer.addView(textButton("DONE") { dismissSheet() })
        }
        return footer
    }

    /** DONE on the expanded sheet: inert in an editor (the preview is visual-only — leave via the
     *  editor bar's Main chip or Cancel/Save); on the real overlay it dismisses the whole panel. */
    private fun dismissSheet() {
        if (isEditor()) return
        hide()
    }

    private fun textButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(style.accentColor)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        val h = dp(10); val v = dp(8); setPadding(h, v, h, v)
        isClickable = true
        setOnClickListener { armAutoHide(); onClick() }
    }

    /** A pill button with a thin outline and transparent fill (Material You "SETTINGS"). */
    private fun outlinedPillButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(style.accentColor)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        val h = dp(20); val v = dp(10); setPadding(h, v, h, v)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(maxOf(1, dp(1)), style.settingsBorderColor ?: style.iconTint)
        }
        isClickable = true
        setOnClickListener { armAutoHide(); onClick() }
    }

    /**
     * A filled pill (tonal) button — the Material You / Android 15 "DONE" button. Its fill and its
     * label are each independently editable (doneBgColor / doneTextColor), falling back to the
     * secondary surface and the text colour they historically shared. A tap on it selects the fill
     * (DONE_BG); the label colour (DONE_TEXT) is reached from its own swatch chip.
     */
    private fun filledPillButton(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(style.doneTextColor ?: style.textColor)
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        val h = dp(24); val v = dp(10); setPadding(h, v, h, v)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(style.doneBgColor ?: style.secondaryContainerColor)
        }
        isClickable = true
        setOnClickListener { armAutoHide(); onClick() }
    }.tagColor(EditableColor.DONE_BG)

    private fun addDivider(container: LinearLayout) {
        container.addView(
            View(context).apply { setBackgroundColor(style.trackColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )
    }

    private fun expand() {
        // Editors are visual-only: the tune/three-dot trigger is inert. Switching to the expanded sheet
        // is done from the editor bar's component chips instead, so the preview never navigates itself.
        if (isEditor()) return
        expanded = true
        ringerMenuOpen = false
        // Slide the "Sound" sheet up from the bottom edge it's anchored to, matching a fresh show.
        render(forceEntrance = true)
    }

    private fun openSoundSettings() {
        // In an editor, SETTINGS/SEE MORE is inert — it's a preview, not a live panel.
        if (preview != null || liveEdit != null) return
        val intent = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        hide()
    }

    private fun ringerIconRes(mode: Int = curRingerMode()): Int {
        // Android 9–11 drew the ringer states as outlined glyphs (hollow bell, slashed hollow
        // bell, compact vibrate) — identical across all three versions; the modern skins keep the
        // filled Material set (or the Android 14 megaphone).
        val legacy = curSkin() == OverlaySkin.ANDROID_9_11
        return when (mode) {
            AudioManager.RINGER_MODE_SILENT -> when {
                isAndroid14IconSet() -> R.drawable.ic_ring_megaphone_off
                legacy -> R.drawable.ic_ring_off_legacy
                else -> R.drawable.ic_ring_off
            }
            AudioManager.RINGER_MODE_VIBRATE ->
                if (legacy) R.drawable.ic_ring_vibrate_legacy else R.drawable.ic_ring_vibrate
            else -> when {
                isAndroid14IconSet() -> R.drawable.ic_ring_megaphone
                legacy -> R.drawable.ic_stream_ring_outline
                else -> R.drawable.ic_stream_ring
            }
        }
    }

    // ── Android 15: redesigned "Sound & vibration" sheet + media-output picker ──────────────────────

    /**
     * The Android 15 sheet: title, an "Audio will play on" output selector, then a filled-pill slider
     * (icon + label inside, trailing dot) per stream and per playing app, with the SETTINGS/DONE
     * footer. Same collapsed panel as Android 14; only this sheet differs.
     */
    private fun populateAndroid15Sheet(container: LinearLayout) {
        loadJob?.cancel()
        container.removeAllViews()
        notifSlider = null
        notifSliderRow = null
        notifDisabledRow = null

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        rows.addView(
            outputCard(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) },
        )

        // Media keeps the note icon, switching only to Android 15's crossed speaker at mute.
        addPillRow(rows, R.drawable.ic_stream_media, "Media", AudioManager.STREAM_MUSIC, mutedIconRes = R.drawable.ic_ring_megaphone_off)
        addPillRow(rows, R.drawable.ic_stream_call, "Call", AudioManager.STREAM_VOICE_CALL)
        // Ring swaps to the vibrate icon at zero; muting it makes Notification unavailable live;
        // raising it back
        // restores the Notification slider — matching the OS's linked ring/notification behaviour.
        addPillRow(
            rows, R.drawable.ic_stream_ring_phone, "Ring", AudioManager.STREAM_RING,
            mutedIconRes = R.drawable.ic_ring_vibrate,
            afterChange = { refreshNotificationAvailability() },
        )
        // Both the active Notification slider and its greyed "unavailable" stand-in are laid out; only
        // the one matching the current ringer state is visible, and they swap live via the Ring row.
        val notif = makePillSlider(R.drawable.ic_stream_notification, "Notification", AudioManager.STREAM_NOTIFICATION)
        val notifDisabled = disabledNotificationRow()
        notifSlider = notif
        notifSliderRow = notif
        notifDisabledRow = notifDisabled
        rows.addView(notif, pillRowParams())
        rows.addView(notifDisabled, pillRowParams())
        refreshNotificationAvailability()
        addPillRow(rows, R.drawable.ic_stream_alarm, "Alarm", AudioManager.STREAM_ALARM)

        val appsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(appsBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        loadPillRowApps(appsBox)

        container.addView(
            cappedScroll(rows),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        container.addView(makeFooter())
    }

    /**
     * The label for the current media output: a connected headset's name (Bluetooth or wired/USB),
     * else "This phone". In an editor it stays the synthetic "This phone" so the preview is stable.
     * Wired/USB outputs have no meaningful product name, so they get a friendly type label; Bluetooth
     * (and hearing aids) report their actual device name.
     */
    private fun currentOutputLabel(): String {
        if (isEditor()) return "This phone"
        val dev = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in EXTERNAL_OUTPUT_TYPES }
        }.getOrNull() ?: return "This phone"
        return when (dev.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB headphones"
            else -> dev.productName?.toString()?.trim()?.ifBlank { "Headphones" } ?: "Headphones"
        }
    }

    /** The "Audio will play on / <output>" card that opens the media-output picker. Its surface is
     *  independently editable (outputSurfaceColor), separate from the DONE pill's own surface. */
    private fun outputCard(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(style.outputSurfaceColor ?: style.secondaryContainerColor, dp(26).toFloat())
            val h = dp(18); val v = dp(12); setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener {
                // In live-edit a tap only recolours this card (resolved as OUTPUT_SURFACE) — the picker
                // is edited on its own via the bar's "Output" switch, so opening it here would fight the
                // editor. On the real overlay it opens the media-output picker as normal.
                if (liveEdit == null && preview == null) { armAutoHide(); openOutputPicker() }
            }
        }.tagColor(EditableColor.OUTPUT_SURFACE)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "Audio will play on"
            setTextColor(style.iconTint)
            textSize = 12f
        })
        col.addView(TextView(context).apply {
            text = currentOutputLabel()
            setTextColor(style.textColor)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(
            ImageView(context).apply { setImageDrawable(tintedIcon(R.drawable.ic_phone_device)) },
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        return card
    }

    private fun pillRowParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) }

    /** Build one Android 15 filled-pill stream row. [afterChange] runs after each volume change (used
     *  by Ring to refresh Notification availability); STREAM_MUSIC is captured as the media slider. */
    private fun makePillSlider(
        iconRes: Int,
        label: String,
        streamType: Int,
        mutedIconRes: Int? = null,
        afterChange: (() -> Unit)? = null,
    ): VolumeSlider {
        lateinit var slider: VolumeSlider
        slider = makeSlider(
            icon = tintedIcon(iconRes), glyph = null, label = label, chevron = ChevronDir.NONE, onChevron = {},
            orientation = SliderOrientation.HORIZONTAL, fill = true, reserveTrailing = false,
            render = SliderRender.PILL_LABEL, mutedIcon = mutedIconRes?.let { tintedIcon(it) },
            minNonZeroFillExtraDp = if (streamType == AudioManager.STREAM_MUSIC) 3f else 0f,
            fadeLabelWhileDragging = true,
        ) { level ->
            setStream(streamType, level)
            afterChange?.invoke()
        }
            .apply {
                onDragStateChange = { dragging -> if (!dragging) level = streamLevel(streamType) }
                level = streamLevel(streamType)
            }
        if (streamType == AudioManager.STREAM_MUSIC) mediaSlider = slider
        return slider
    }

    private fun addPillRow(
        container: LinearLayout,
        iconRes: Int,
        label: String,
        streamType: Int,
        mutedIconRes: Int? = null,
        afterChange: (() -> Unit)? = null,
    ) {
        container.addView(makePillSlider(iconRes, label, streamType, mutedIconRes, afterChange), pillRowParams())
    }

    private fun loadPillRowApps(container: LinearLayout) {
        startAppSync(container) { app ->
            makeSlider(
                icon = app.icon, glyph = null, label = app.label, chevron = ChevronDir.NONE, onChevron = {},
                orientation = SliderOrientation.HORIZONTAL, fill = true, reserveTrailing = false,
                render = SliderRender.PILL_LABEL, recolorIcon = false, // keep full-colour app icons
            ) { level -> appVolume.setVolume(app.pkg, level, app.piids) }
                .apply {
                    level = appVolume.volumeFor(app.pkg)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
                        .apply { bottomMargin = dp(10) }
                }
        }
    }

    /** A greyed, non-interactive Notification row shown when the ringer is muted. */
    private fun disabledNotificationRow(): View {
        val row = FrameLayout(context).apply {
            background = roundedBg(style.trackColor, dp(26).toFloat())
        }
        row.addView(
            ImageView(context).apply {
                setImageDrawable(tintedIcon(R.drawable.ic_ring_vibrate, fade(style.iconTint, 0.55f)))
                val p = dp(8); setPadding(p, p, p, p)
            },
            FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8)
            },
        )
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "Notification"
            setTextColor(fade(style.textColor, 0.6f))
            textSize = 15f
        })
        col.addView(TextView(context).apply {
            text = "Unavailable because ring is muted"
            setTextColor(fade(style.textColor, 0.45f))
            textSize = 11f
        })
        row.addView(
            col,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(56)
            },
        )
        return row
    }

    /** The ringer is muted (vibrate or silent) → notification volume is unavailable. */
    private fun isRingMuted() = curRingerMode() != AudioManager.RINGER_MODE_NORMAL

    /** Reflect the ringer state on the Notification row: an active slider when the ring is on, hidden
     *  behind the greyed "unavailable" row when the ring is muted (so it can't be adjusted then). */
    private fun refreshNotificationAvailability() {
        val muted = isRingMuted()
        notifSlider?.let {
            it.visibility = if (muted) View.GONE else View.VISIBLE
            if (!muted) it.level = streamLevel(AudioManager.STREAM_NOTIFICATION)
        }
        notifDisabledRow?.visibility = if (muted) View.VISIBLE else View.GONE
    }

    /**
     * The Notification row's muted stand-in (Android 14 sheet): laid out exactly like a live row —
     * leading bell + "Notification volume" label — but with "Unavailable because ring is muted" in
     * place of the slider, on the plain sheet background (no chip).
     */
    private fun disabledNotificationRow911(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = dp(16); val v = if (isModernSheet()) dp(14) else dp(8); setPadding(h, v, h, v)
        }
        row.addView(
            ImageView(context).apply { setImageDrawable(tintedIcon(R.drawable.ic_stream_notification)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
        )
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = "Notification volume"
            setTextColor(style.textColor)
            textSize = 14f
            // Same leading indent as the live rows' labels so the two states line up when swapped.
            setPadding(dp(9), 0, 0, 0)
        })
        col.addView(TextView(context).apply {
            text = "Unavailable because ring is muted"
            setTextColor(fade(style.textColor, 0.6f))
            textSize = 13f
            setPadding(dp(9), 0, 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun isRingStreamMuted() = streamLevel(AudioManager.STREAM_RING) <= 0.001f

    private fun refreshExpandedNotificationAvailability() {
        val muted = isRingStreamMuted()
        notifSliderRow?.visibility = if (muted) View.GONE else View.VISIBLE
        notifSlider?.let { if (!muted) it.level = streamLevel(AudioManager.STREAM_NOTIFICATION) }
        notifDisabledRow?.visibility = if (muted) View.VISIBLE else View.GONE
    }

    /**
     * The media-output picker (Android 15): a modal layered over the (dimmed) Sound sheet in the
     * *same* window — a full-screen dim scrim plus the centred card. Because the sheet window is never
     * torn down, the picker can expand/collapse smoothly instead of a close-and-reopen. Tapping the
     * dimmed area (or DONE) collapses it back to the sheet.
     */
    private fun buildPickerOverlay(): FrameLayout {
        val overlay = FrameLayout(context)
        val dim = View(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            isClickable = true
            setOnClickListener { dismissPicker() }
        }
        overlay.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // While editing the Media output, dock the picker to the top so the color bar (parked at the
        // bottom) doesn't overlap it; otherwise it sits centred as normal.
        val editingOutput = isEditor() && liveEdit?.component == PanelComponent.OUTPUT
        // Landscape is far wider than the picker needs, so cap it to a phone-like width and centre it
        // (a full-width card looks stretched); portrait fills the width minus side margins as before.
        val landscape = curOrientation() == EditOrientation.LANDSCAPE
        val cardW = if (landscape) dp(400) else FrameLayout.LayoutParams.MATCH_PARENT
        overlay.addView(
            buildOutputPickerCard(),
            FrameLayout.LayoutParams(
                cardW, FrameLayout.LayoutParams.WRAP_CONTENT,
                if (editingOutput) Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else Gravity.CENTER or Gravity.CENTER_HORIZONTAL,
            ).apply {
                if (!landscape) { leftMargin = dp(28); rightMargin = dp(28) }
                if (editingOutput) topMargin = dp(40)
            },
        )
        return overlay
    }

    private fun buildOutputPickerCard(): View {
        // The picker is its own editing surface (PanelComponent.OUTPUT), so it always renders with the
        // OUTPUT component's colours — whether it's the surface being edited now, or the saved set
        // applied when the real overlay opens the picker. Swap the working style to one carrying those
        // overrides for the duration of the build, so the split DONE pill and the pill's dot (which
        // read the member [style]) pick them up too, then restore it.
        val pickerColors = pickerOverrides()
        val savedStyle = style
        style = styleFor(curSkin(), isDark()).applyColors(pickerColors)
        // The picker's DONE pill has its own fill/text in both themes (a light-blue pill with dark-navy
        // label in dark; a deep-blue pill with near-white label in light) rather than the sheet's
        // secondary surface / body text. Seed those onto the working style so filledPillButton picks
        // them up, unless the user has pinned their own OUTPUT_DONE colours (those already rode in via
        // applyColors above and are kept).
        style = style.copy(
            doneBgColor = pickerColors.doneBg
                ?: if (isDark()) OUTPUT_PICKER_REF_DONE_BG_DARK else OUTPUT_PICKER_REF_DONE_BG,
            doneTextColor = pickerColors.doneText
                ?: if (isDark()) OUTPUT_PICKER_REF_DONE_TEXT_DARK else OUTPUT_PICKER_REF_DONE_TEXT,
        )
        return try {
            buildOutputPickerCardInner(pickerColors)
        } finally {
            style = savedStyle
        }
    }

    private fun buildOutputPickerCardInner(pickerColors: PanelColors): View {
        // It has a bespoke reference palette (the light-blue art in light theme, a deep-navy dark skin
        // in dark theme) rather than the sheet's palette, so its surface elements read their own raw
        // overrides directly and fall back to those reference defaults — independent of the sheet.
        val dark = isDark()
        val refTrack = if (dark) OUTPUT_PICKER_REF_TRACK_DARK else OUTPUT_PICKER_REF_TRACK
        val refFill = if (dark) OUTPUT_PICKER_REF_FILL_DARK else OUTPUT_PICKER_REF_FILL
        val refContent = if (dark) OUTPUT_PICKER_REF_CONTENT_DARK else OUTPUT_PICKER_REF_CONTENT
        val cardBg = pickerColors.container ?: if (dark) style.containerColor else OUTPUT_PICKER_REF_CARD
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardBg, dp(28).toFloat())
            setPadding(dp(20), dp(20), dp(20), dp(14))
            // Swallow taps on the card's own empty areas so they don't fall through to the dim (which
            // would close the picker); the slider/buttons still handle their own touches.
            isClickable = true
        }.tagColor(EditableColor.OUTPUT_CARD)

        lateinit var phone: VolumeSlider
        phone = makeSlider(
            icon = tintedIcon(R.drawable.ic_speaker), glyph = null, label = currentOutputLabel(),
            chevron = ChevronDir.NONE, onChevron = {},
            orientation = SliderOrientation.HORIZONTAL, fill = true, reserveTrailing = false,
            render = SliderRender.PILL_LABEL, percentWhileDragging = true,
            // Straight (flat) fill edge, while the outer pill keeps its rounded corners.
            squareFill = true,
            mutedIcon = tintedIcon(R.drawable.ic_speaker_off),
            minNonZeroFillExtraDp = 0f,
            fadeLabelWhileDragging = false,
            // Media-output picker palette (matches the reference art): in light theme a light-blue
            // fill over a lighter track with dark-navy content; in dark theme a deep-navy fill/track
            // under a light-blue content colour for the text/percentage/icon. Each is user-overridable
            // via the OUTPUT component's own slider fill / track / text colours; the trailing dot
            // follows the dot override. Defaults are shared with the swatch seeds (defaultColor).
            pickerTrackColor = pickerColors.track ?: refTrack,
            pickerFillColor = pickerColors.fill ?: refFill,
            pickerContentColor = pickerColors.text ?: refContent,
        ) { level -> setStream(AudioManager.STREAM_MUSIC, level) }
            .apply {
                onDragStateChange = { dragging -> if (!dragging) level = streamLevel(AudioManager.STREAM_MUSIC) }
                level = streamLevel(AudioManager.STREAM_MUSIC)
            }
            .tagColor(EditableColor.OUTPUT_SLIDER)
        mediaSlider = phone
        card.addView(
            phone,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(10) },
        )

        val connectSurface = pickerColors.secondary
            ?: if (dark) OUTPUT_PICKER_REF_CONNECT_DARK else OUTPUT_PICKER_REF_CONNECT
        val connect = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(connectSurface, dp(12).toFloat())
            val h = dp(18); val v = dp(16); setPadding(h, v, h, v)
            isClickable = true
            setOnClickListener { openConnectedDevices() }
        }.tagColor(EditableColor.OUTPUT_CONNECT)
        connect.addView(
            // The "+" defaults to the picker's content colour in dark theme (matching the label /
            // percentage / dot), or the neutral icon tint in light theme — either way user-overridable
            // via the OUTPUT component's own icon colour.
            ImageView(context).apply { setImageDrawable(tintedIcon(R.drawable.ic_add, pickerColors.icon ?: refContent)) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(16) },
        )
        connect.addView(TextView(context).apply {
            text = "Connect a device"
            // Default to the picker's content colour (shared with the OUTPUT_PICKER_TEXT swatch) so the
            // "Text" swatch and this label agree on their default.
            setTextColor(pickerColors.text ?: refContent)
            textSize = 15f
            // A touch heavier than the body default (both themes) so the label reads a little bolder.
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        card.addView(connect, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(14), 0, 0)
        }
        // Re-tag the DONE pill with the picker's own identity (overriding filledPillButton's default
        // DONE_BG tag) so a tap selects the Media-output DONE, not the Expanded sheet's.
        footer.addView(filledPillButton("DONE") { dismissPicker() }.tagColor(EditableColor.OUTPUT_DONE))
        card.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return card
    }

    /** User-facing "close the picker" (DONE / tap-outside). On the real overlay it collapses back to
     *  the sheet; in any editor it's inert — the preview is visual-only, and the picker is left via
     *  the editor bar's component switch. */
    private fun dismissPicker() {
        if (isEditor()) return
        closeOutputPicker()
    }

    /** The raw colour overrides that recolour the output picker: the OUTPUT component's own set while
     *  it's the one being edited, else none (the live overlay's picker keeps its reference palette;
     *  saved OUTPUT customization is applied when the real panel later opens the picker — see below). */
    private fun pickerOverrides(): PanelColors = when {
        // Editing the picker directly: its live working colours.
        liveEdit?.component == PanelComponent.OUTPUT || preview?.component == PanelComponent.OUTPUT ->
            activeColors()
        // Real overlay (or sheet preview): the picker isn't the active component, so pull the saved
        // OUTPUT set for this version so a user's picker colours still show when it's opened for real.
        else -> currentCustomization().component(PanelComponent.OUTPUT).colors
    }

    /** Expand the picker over the current sheet with a scale+fade, keeping the window intact. */
    private fun openOutputPicker() {
        val scrim = root ?: return
        if (pickerOverlay != null) return
        outputPicker = true
        handler.removeCallbacks(hideRunnable)
        savedSheetMediaSlider = mediaSlider // the picker's own pill takes over volume keys meanwhile
        val overlay = buildPickerOverlay()
        pickerOverlay = overlay
        scrim.addView(
            overlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val dim = overlay.getChildAt(0)
        val card = overlay.getChildAt(1)
        dim.alpha = 0f
        dim.animate().alpha(1f).setDuration(PICKER_IN_MS).start()
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.post {
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(DecelerateInterpolator()).setDuration(PICKER_IN_MS).start()
        }
    }

    /** Collapse the picker back to the sheet with the reverse scale+fade, then remove it. */
    private fun closeOutputPicker() {
        val overlay = pickerOverlay
        outputPicker = false
        if (overlay == null) return
        pickerOverlay = null
        // Hand volume-key control back to the sheet's media slider, refreshed to the current level.
        mediaSlider = savedSheetMediaSlider?.also { it.level = streamLevel(AudioManager.STREAM_MUSIC) }
        savedSheetMediaSlider = null
        val dim = overlay.getChildAt(0)
        val card = overlay.getChildAt(1)
        dim.animate().alpha(0f).setDuration(PICKER_OUT_MS).start()
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setInterpolator(AccelerateInterpolator()).setDuration(PICKER_OUT_MS)
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
            .start()
        armAutoHide()
    }

    private fun openConnectedDevices() {
        // In an editor this is inert — a preview never leaves the app.
        if (preview != null || liveEdit != null) return
        // The Connected devices dashboard has no stable public action; try its component, then fall
        // back to Bluetooth settings (which lives under Connected devices on modern Android).
        val dashboard = Intent()
            .setClassName("com.android.settings", "com.android.settings.Settings\$ConnectedDeviceDashboardActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(dashboard) }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        hide()
    }

    // ── Material You (Android 12): one integrated, animated card ───────────────────────────────────

    /**
     * The Android 12 collapsed panel: a single rounded card holding the ringer control, the filled
     * media pill and the tune footer. The ringer shows only the current mode; tapping it reveals the
     * other modes inline (Vibrate / Silent / Notification) — the reveal is animated by a
     * [LayoutTransition], which also grows the WRAP_CONTENT window smoothly.
     */
    private fun populateMaterialYouCollapsed(container: LinearLayout) {
        loadJob?.cancel()
        container.removeAllViews()
        val corner = dp(style.containerCornerDp).toFloat()
        val cardW = dp(style.pillThicknessDp) + dp(14)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(style.containerColor, corner)
            setPadding(0, dp(8), 0, 0)
            // Animate the ringer modes appearing/disappearing (and the card/window resizing to fit).
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
                setDuration(170)
            }
        }

        // Ringer buttons in fixed order (top→bottom: vibrate, silent, notification/normal); only the
        // active one is visible until the control is opened. Square so the selected-mode oval
        // background renders as a perfect circle.
        val ringSize = dp(style.pillThicknessDp) + dp(4)
        val modeButtons = LinkedHashMap<Int, ImageView>()
        intArrayOf(
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_NORMAL,
        ).forEach { mode ->
            val btn = ImageView(context).apply {
                val p = dp(10); setPadding(p, p, p, p)
                setOnClickListener {
                    if (isEditor()) return@setOnClickListener // visual-only preview
                    armAutoHide() // interacting keeps the panel alive
                    if (ringerMenuOpen) {
                        ringerMenuOpen = false
                        setRinger(mode) // may route to DND-access settings and hide()
                    } else {
                        ringerMenuOpen = true // first tap on the current mode opens the chooser
                    }
                    refreshRingerViews()
                }
            }
            modeButtons[mode] = btn
            card.addView(
                btn,
                LinearLayout.LayoutParams(ringSize, ringSize).apply { topMargin = dp(2); bottomMargin = dp(2) },
            )
        }
        ringerButtons = modeButtons
        refreshRingerViews()

        // Thin-track media slider with a wide rounded fill capsule; the note rides inside the capsule
        // (tinted on-accent so it reads against the fill).
        lateinit var slider: VolumeSlider
        // The note rides inside the accent-filled capsule, so by default it's tinted to contrast the
        // *fill* — an automatic on-fill colour, so recolouring background/fill never drags it. A user
        // "Media note icon" override (style.mediaIconColor) pins it to an exact colour instead.
        val onFill = style.mediaIconColor ?: contrastOn(style.fillColor)
        slider = makeSlider(
            icon = tintedIcon(R.drawable.ic_stream_media, onFill),
            glyph = null, label = null, chevron = ChevronDir.NONE, onChevron = {},
            orientation = SliderOrientation.VERTICAL, fill = true, reserveTrailing = false,
            mutedIcon = tintedIcon(R.drawable.ic_stream_media_off, onFill),
            // Android 15 gives the media slider a full-width shaped pill background (the track color)
            // rather than the thin centre stick Android 12–14 keep.
            capsuleFullTrack = isAndroid15Sheet(),
        ) { level -> setStream(AudioManager.STREAM_MUSIC, level) }
            .apply {
                onDragStateChange = { dragging -> if (!dragging) level = streamLevel(AudioManager.STREAM_MUSIC) }
                level = streamLevel(AudioManager.STREAM_MUSIC)
            }
        mediaSlider = slider
        card.addView(
            slider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(188)).apply { topMargin = dp(30) },
        )

        // Android 12–15's overflow: a three-dot button on the card's own background. Its own editable
        // colour (overflowColor) when the user pins one, else the theme accent as before.
        val more = ImageView(context).apply {
            setImageDrawable(tintedIcon(R.drawable.ic_more_horiz, style.overflowColor ?: style.accentColor))
            setPadding(dp(9), dp(10), dp(9), dp(12))
            setOnClickListener { expand() }
        }.tagColor(EditableColor.OVERFLOW)
        card.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        container.addView(card, LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Retint the ringer buttons and show one (collapsed) or all (open) modes. The selected mode is a
     * filled circle — [accentColor] disc with an on-accent ([containerColor]) icon — so it reads as a
     * light-blue circle in dark mode and a teal circle in light mode; the others are bare tinted
     * icons.
     */
    private fun refreshRingerButtons(buttons: Map<Int, ImageView>) {
        val current = curRingerMode()
        buttons.forEach { (mode, btn) ->
            val active = mode == current
            // The active mode's icon sits on the accent disc → contrast the accent automatically (or
            // the "Active mode icon" override, when set); the inactive icons use the editable icon tint.
            val activeTint = style.modeIconColor ?: contrastOn(style.accentColor)
            btn.setImageDrawable(tintedIcon(ringerIconRes(mode), if (active) activeTint else style.iconTint))
            btn.background = if (active) ovalBg(style.accentColor) else null
            btn.visibility = if (ringerMenuOpen || active) View.VISIBLE else View.GONE
        }
    }

    /** Repaint whichever ringer control is on screen (Android 9–11's single button, or Android
     *  12–15's mode row) from the current mode — no panel rebuild. */
    private fun refreshRingerViews() {
        ringerIcon?.setImageDrawable(tintedIcon(ringerIconRes(), style.modeIconColor ?: style.iconTint))
        ringerButtons?.let { refreshRingerButtons(it) }
    }

    private fun cycleRinger() {
        val order = intArrayOf(
            AudioManager.RINGER_MODE_NORMAL,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT,
        )
        // Step from what was last asked for, not from the live mode: where the platform refuses silent
        // (see [silenceRinger]) the ringer stays on vibrate, and cycling from *that* would ask for
        // silent again on every tap — leaving the button stuck on vibrate with no way back to normal.
        val start = order.indexOf(requestedRingerMode ?: curRingerMode()).coerceAtLeast(0)
        setRinger(order[(start + 1) % order.size])
    }

    /**
     * Switch the ringer to [mode], synchronously. Silent takes the [silenceRinger] route so it only
     * mutes the ring volume and never drags Do Not Disturb along; the other modes are a plain write,
     * which on some builds needs Do-Not-Disturb / notification-policy access — without it the system
     * refuses it, and we then send the user to the access screen so the mode becomes available,
     * matching the stock volume dialog.
     *
     * Every route here settles before returning, so callers can repaint straight from
     * [curRingerMode] and what they draw is what the device is actually in — including when the
     * platform grants something other than [mode].
     */
    private fun setRinger(mode: Int) {
        // In an editor the ringer is synthetic — flip the demo state only, never the real ringer.
        preview?.let { it.ringerMode = mode; return }
        liveEdit?.let { it.ringerMode = mode; return }
        // This selection supersedes any silent request still crossing to the privileged service.
        pendingSilent = false
        requestedRingerMode = mode
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            silenceRinger()
            return
        }
        runCatching { audioManager.ringerMode = mode }
        // A short haptic tick confirms the vibrate selection (all rich panels: Android 9–11 onwards).
        if (mode == AudioManager.RINGER_MODE_VIBRATE) vibrateOnce()
        // Leaving (or entering) silent counts as a zen change, so the platform throws for it without
        // notification-policy access. It's the only reason a plain write is refused, so a mode that
        // didn't take while access is missing means exactly that — offer the access screen.
        if (audioManager.ringerMode != mode && !notificationManager.isNotificationPolicyAccessGranted) {
            requestDndAccess()
        }
    }

    /**
     * Select silent without ever letting Do Not Disturb come along.
     *
     * [AudioManager.setRingerMode] is the framework's *external* ringer path, and its zen helper
     * switches Do Not Disturb on ("Alarms only") whenever an app asks that way for silent while zen
     * is off — that, not this app, is what used to enable DND here (it's unconditional in
     * ZenModeHelper on Android 9–15, which is why the panel could never opt out of it). The internal
     * setter has no such link, but it's behind `enforceVolumeController`, so only the system volume
     * panel may call it directly. Hence two routes, best first:
     *
     *  1. **The privileged service.** `cmd audio set-ringer-mode SILENT` lands on that internal
     *     setter, so it selects real silent and leaves zen exactly where it was. It runs in the
     *     Shizuku process, so it needs Shizuku connected and a platform new enough to carry the
     *     sub-command — hence the fallback below when it isn't available.
     *  2. **The volume-key path**, which any app may use: mute the ring stream, dropping the ringer
     *     to vibrate (straight to silent where there's no vibrator), then step down once more to try
     *     for silent. Muting first also clears what would otherwise block that step — the platform
     *     ignores a "lower" that repeats the previous direction, and debounces one arriving just
     *     after a lower that itself dropped normal → vibrate; a mute is neither, so the two run
     *     back-to-back with nothing to wait for. FLAG_ALLOW_RINGER_MODES asks for the ringer-mode
     *     step explicitly, so this keeps working on builds where ring isn't the stream carrying the
     *     ringer modes (Android 15's split ring/notification volumes).
     *
     * **Route 2 usually stops at vibrate, and that's a permission, not a race.** AudioService's
     * `checkForRingerModeChange` only converts vibrate → silent when its
     * `VolumePolicy.volumeDownToEnterSilent` is set, and SystemUI — the only caller allowed to set
     * that policy — has left it off since Oreo (the same reason the hardware keys can't reach silent
     * on stock Android either). So on a phone with a vibrator the mute is as far as it goes. The
     * ring is muted either way, and the panel then repaints from the real mode, showing vibrate —
     * what the device is genuinely in — rather than a silent it isn't. Turning DND on to force the
     * point is deliberately not an option here, with or without a fallback.
     */
    private fun silenceRinger() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        if (ShizukuManager.isReady) {
            // Crosses a process boundary, so it can't be awaited inline. Show silent while it's in
            // flight — the tap has to land immediately — and settle on the truth either way.
            pendingSilent = true
            scope.launch {
                ShizukuManager.setRingerMode(SHELL_RINGER_SILENT)
                // A newer selection may have superseded this one while the shell call ran; it owns
                // the ringer now, so leave whatever it chose alone.
                if (requestedRingerMode != AudioManager.RINGER_MODE_SILENT) return@launch
                pendingSilent = false
                // Judge it by where the ringer actually landed, never by the command's result: a
                // platform without the sub-command exits 0 and prints nothing, so success and
                // "silently did nothing" are indistinguishable from the shell's side.
                if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) muteRingStream()
                refreshRingerViews()
            }
            return
        }
        muteRingStream()
    }

    /** Route 2 of [silenceRinger]: the volume-key path every app may use. Both steps complete inside
     *  their binder call, so the mode is settled by the time this returns. */
    private fun muteRingStream() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val flags = AudioManager.FLAG_ALLOW_RINGER_MODES
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, flags)
        }
        // Reached outright on a device with no vibrator, where muting the ring *is* silent.
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER, flags)
        }
    }

    /** One short buzz — feedback when the user picks the vibrate ringer mode. */
    private fun vibrateOnce() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(120)
            }
        }
    }

    private fun requestDndAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        hide()
    }

    // ── shared slider/data helpers ────────────────────────────────────────────────────────────────

    private fun makeSlider(
        icon: Drawable?,
        glyph: String?,
        label: String?,
        chevron: ChevronDir,
        onChevron: () -> Unit,
        orientation: SliderOrientation = style.orientation,
        fill: Boolean = style.stretch,
        reserveTrailing: Boolean = style.render == SliderRender.LINE_THUMB,
        render: SliderRender = style.render,
        capsuleFullTrack: Boolean = false,
        squareFill: Boolean = false,
        percentWhileDragging: Boolean = false,
        recolorIcon: Boolean = true,
        mutedIcon: Drawable? = null,
        pickerTrackColor: Int? = null,
        pickerFillColor: Int? = null,
        pickerContentColor: Int? = null,
        minNonZeroFillExtraDp: Float = 0f,
        fadeLabelWhileDragging: Boolean = false,
        pressedHalo: Boolean = false,
        motionFollowScale: Float = prefs.getHoldFollowScale(),
        motionSettleScale: Float = prefs.getHoldSettleScale(),
        // Editors are visual-only: every slider is built non-interactive so a drag never moves the
        // demo level (a tap still resolves to its colour via the editor's own hit-test layer).
        interactive: Boolean = !isEditor(),
        onChange: (Float) -> Unit,
    ) = VolumeSlider(
        context = context,
        style = style,
        icon = icon,
        glyph = glyph,
        label = label,
        chevron = chevron,
        orientation = orientation,
        fill = fill,
        reserveTrailing = reserveTrailing,
        render = render,
        capsuleFullTrack = capsuleFullTrack,
        squareFill = squareFill,
        percentWhileDragging = percentWhileDragging,
        recolorIcon = recolorIcon,
        mutedIcon = mutedIcon,
        pickerTrackColor = pickerTrackColor,
        pickerFillColor = pickerFillColor,
        pickerContentColor = pickerContentColor,
        minNonZeroFillExtraDp = minNonZeroFillExtraDp,
        fadeLabelWhileDragging = fadeLabelWhileDragging,
        pressedHalo = pressedHalo,
        motionFollowScale = motionFollowScale,
        motionSettleScale = motionSettleScale,
        interactive = interactive,
        onLevelChange = { onChange(it); armAutoHide() },
        onTouchStart = { handler.removeCallbacks(hideRunnable) },
        onTouchEnd = { armAutoHide() },
        onChevronClick = onChevron,
    )

    private fun setStream(
        streamType: Int,
        level: Float,
        tieNotification: Boolean = false,
        tieCall: Boolean = false,
    ): Float {
        // Any editor (the embedded preview or the on-screen position/colour editor) must never touch
        // the device's real volumes: interaction is for preview only, kept to synthetic state.
        preview?.let { it.streamLevels[streamType] = level.coerceIn(0f, 1f); return level.coerceIn(0f, 1f) }
        liveEdit?.let { it.streamLevels[streamType] = level.coerceIn(0f, 1f); return level.coerceIn(0f, 1f) }
        val max = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val target = (level * max).roundToInt().coerceIn(0, max)
        runCatching { audioManager.setStreamVolume(streamType, target, 0) }
        // Combined "Ring & notification" rows drive notification alongside ring; the Android 14 icon
        // set (separate sliders) leaves them independent.
        if (tieNotification && streamType == AudioManager.STREAM_RING) {
            val maxN = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, (level * maxN).roundToInt(), 0)
            }
        }
        // Android 7–8 folds the call (in-call) volume into the same Ring bar, so moving ring drives
        // the voice-call stream in lock-step too.
        if (tieCall && streamType == AudioManager.STREAM_RING) {
            val maxC = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (level * maxC).roundToInt(), 0)
            }
        }
        // Dragging the ring bar all the way to the left must actually silence it (and, being merged,
        // notification). The platform can refuse to take the ring stream to 0 while it's in normal
        // mode — it clamps back to 1 — so force the ringer into vibrate, which genuinely mutes both.
        if (streamType == AudioManager.STREAM_RING && target == 0 &&
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
        ) {
            runCatching { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
        }
        return target / max.toFloat()
    }

    private fun streamLevel(streamType: Int): Float {
        preview?.let { return it.streamLevels[streamType] ?: 0.5f }
        liveEdit?.let { return it.streamLevels[streamType] ?: 0.5f }
        val max = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        return audioManager.getStreamVolume(streamType) / max.toFloat()
    }

    /**
     * Point the per-app box at [container] and remember [builder] (one row per app), then load the
     * current apps and start the refresh timer so the list stays in sync while the panel is open.
     */
    private fun startAppSync(container: LinearLayout, builder: (AppPlayers) -> View) {
        appsBox = container
        appBuilder = builder
        handler.removeCallbacks(appsSyncRunnable)
        syncAppSliders(schedule = true)
    }

    private fun stopAppSync() {
        handler.removeCallbacks(appsSyncRunnable)
        appsBox = null
        appBuilder = null
    }

    /**
     * Re-query the active players and reconcile the on-screen rows with them: drop rows for apps that
     * stopped, append rows for apps that just started, and leave existing rows (with any volume the
     * user set) untouched. When [schedule] is set, re-arm the timer so the list keeps tracking while
     * the panel stays open.
     */
    private fun syncAppSliders(schedule: Boolean) {
        val container = appsBox ?: return
        val builder = appBuilder ?: return
        if (root == null || dismissing) return
        loadJob?.cancel()
        loadJob = scope.launch {
            val apps = queryApps()
            // The panel may have been rebuilt or torn down while the query ran off-thread.
            if (appsBox === container && root != null && !dismissing) {
                diffAppSliders(container, builder, apps)
                // The preview never adds live app rows, so it needs no polling timer.
                if (schedule && preview == null) {
                    handler.removeCallbacks(appsSyncRunnable)
                    handler.postDelayed(appsSyncRunnable, APPS_SYNC_MS)
                }
            }
        }
    }

    /** Reconcile [container]'s children (each tagged with its package) against [apps]. */
    private fun diffAppSliders(
        container: LinearLayout,
        builder: (AppPlayers) -> View,
        apps: List<AppPlayers>,
    ) {
        val wanted = apps.map { it.pkg }.toHashSet()
        // Remove rows whose app is no longer playing, so stale controls never linger.
        for (i in container.childCount - 1 downTo 0) {
            if ((container.getChildAt(i).tag as? String) !in wanted) container.removeViewAt(i)
        }
        val present = (0 until container.childCount)
            .mapNotNull { container.getChildAt(it).tag as? String }
            .toHashSet()
        // Append a row for each newly-playing app; the builder sets its own layout params.
        apps.forEach { app ->
            if (app.pkg !in present) container.addView(builder(app).apply { tag = app.pkg })
        }
        // Keep the Android 7–8 selection consistent: a just-added app row must start greyed if some
        // other row is the selected one. No-op on skins without a generic selection.
        applyGenericSelection()
    }

    /**
     * Query the currently-playing, non-system apps (deduped per package, capped). The foreground app,
     * when it's among those playing, is ordered first so the app the user is actually in leads the
     * list, with the remaining background apps following in their playback order.
     */
    private suspend fun queryApps(): List<AppPlayers> {
        // The editor preview shows the panel's own controls only — no live per-app sliders.
        if (preview != null) return emptyList()
        // Nothing audible on the media output → no per-app sliders. Some apps (e.g. Facebook) keep a
        // "started" audio player registered while silent/backgrounded, and the OS still lists it as an
        // active playback configuration; isMusicActive() reflects real output, so it clears those
        // stale entries when the user isn't actually listening to anything.
        if (!audioManager.isMusicActive()) return emptyList()
        val players = if (ShizukuManager.isReady) ShizukuManager.getActivePlayers() else emptyList()
        val pm = context.packageManager
        val foreground = foregroundPackage
        return players
            .filter { it.packageName != context.packageName && !isSystem(it.packageName) }
            .groupBy { it.packageName }
            .entries
            // Stable sort: the foreground app rises to the top; everything else keeps playback order.
            .sortedByDescending { it.key == foreground }
            .take(MAX_APP_PILLS)
            .map { (pkg, group) ->
                AppPlayers(
                    pkg = pkg,
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg),
                    icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull(),
                    piids = group.map { it.piid },
                )
            }
    }

    private fun tintedIcon(res: Int, color: Int = style.iconTint): Drawable? {
        val d = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
        DrawableCompat.setTint(d, color)
        return d
    }

    /**
     * Opt [view] (and its whole subtree) out of the platform's Force Dark / "extended" auto-dark, so
     * the system never re-inverts the colours we set. Overlay windows are added straight to the
     * WindowManager from a bare context, so unlike the activity they don't inherit the app theme's
     * `forceDarkAllowed=false` — without this, forced dark mode silently shifts the panel's colours
     * (and makes an edit to one colour look like it changes others). No-op below API 29.
     */
    private fun disableForceDark(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.isForceDarkAllowed = false
        }
    }

    private fun roundedBg(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun ovalBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** [color] scaled to [alpha] of its current opacity — for greyed-out (disabled) elements. */
    private fun fade(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * A legible on-[background] colour (black or white by luminance). Used for icons/labels that sit
     * *inside* a filled control (e.g. the media note on the accent capsule): they need to contrast the
     * fill, not track any editable colour — so recolouring the background/fill never drags them along,
     * and they stay readable at any colour the user picks.
     */
    private fun contrastOn(background: Int): Int {
        val lum = (0.299 * Color.red(background) + 0.587 * Color.green(background) +
            0.114 * Color.blue(background)) / 255.0
        return if (lum > 0.5) Color.BLACK else Color.WHITE
    }

    private fun roundedBg(color: Int, tl: Float, tr: Float, br: Float, bl: Float) = GradientDrawable().apply {
        cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
        setColor(color)
    }

    /**
     * Whether the overlay should use its dark palette. Follows the app's own Light/Dark/System theme
     * choice (the same setting the main UI obeys) rather than only the device night setting, so
     * forcing Light/Dark re-skins the panel too. Re-read on every [show]/[render], so a change applies
     * to the next time the panel appears. Only [ThemeMode.SYSTEM] falls back to the device setting.
     */
    private fun isDark(): Boolean = when (themePrefs.getThemeMode()) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun isSystem(pkg: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updated = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        system && !updated
    }.getOrDefault(true)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private companion object {
        const val AUTO_HIDE_MS = 4000L
        const val AUTO_HIDE_78_MS = 6000L
        const val SLIDE_IN_MS = 220L
        const val SLIDE_OUT_MS = 200L
        const val PICKER_IN_MS = 220L
        const val PICKER_OUT_MS = 160L
        const val MAX_APP_PILLS = 4

        /** The panel can be dragged freely; the offset is only clamped so at least this much (dp) of
         *  it stays on-screen, so it never disappears entirely out of reach. */
        const val KEEP_ON_SCREEN_DP = 48f

        /** How often an open panel re-checks which apps are playing, to add/remove per-app sliders. */
        const val APPS_SYNC_MS = 1500L

        /** The mode name `cmd audio set-ringer-mode` takes for silent (see [silenceRinger]). */
        const val SHELL_RINGER_SILENT = "SILENT"

        /** Output device types that count as an external headset (Bluetooth or wired/USB) — when one is
         *  connected the media-output label shows its name instead of "This phone". These are all
         *  compile-time int constants, so referencing newer ones is safe on older runtimes. */
        val EXTERNAL_OUTPUT_TYPES = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
