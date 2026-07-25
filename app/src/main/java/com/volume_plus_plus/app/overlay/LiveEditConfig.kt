package com.volume_plus_plus.app.overlay

import android.graphics.Rect
import android.media.AudioManager

/** What the on-screen live editor is editing: the panel's position, or its colours. */
enum class LiveEditMode { POSITION, COLOR }

/**
 * Drives an [OverlayController] in **live-edit** mode: the real panel is drawn on top of the user's
 * current screen (via the WindowManager, exactly where it will really dock), but instead of behaving
 * like the live overlay it's a WYSIWYG editor — a [LiveEditMode.POSITION] editor (drag the panel to
 * move it) or a [LiveEditMode.COLOR] editor (tap an element to recolour it). Either way its own
 * controls stay interactive for preview, but read/write only synthetic state so the device's real
 * volume/ringer are never touched, and it never dismisses itself.
 *
 * Edits accumulate in [working] and are persisted only when the session's Save is pressed; Cancel
 * discards [working] untouched.
 */
class LiveEditConfig(
    /** The style being edited (fixes skin + sub-version regardless of the saved pref). */
    val version: OverlayVersion,
    /** Which orientation's position is being edited — the device is held this way while editing. */
    val orientation: EditOrientation,
    /** The in-progress customization, seeded from the saved one; mutated as the user edits. */
    var working: VersionCustomization,
    /** Whether this session edits the panel's position or its colours. */
    val mode: LiveEditMode = LiveEditMode.POSITION,
) {
    /** Which component is being edited — the main panel or (Android 9+) the expanded sheet. */
    var component: PanelComponent = PanelComponent.MAIN

    /** Reports the panel's current on-screen bounds so the floating control bar can park clear of it. */
    var onPanelBounds: ((Rect) -> Unit)? = null

    /** Fired when the component changes from inside the panel (e.g. tapping tune to expand) so the
     *  floating bar's Main/Expanded switch can stay in sync. */
    var onComponentChanged: ((PanelComponent) -> Unit)? = null

    /** COLOR mode: fired when an element is tapped in the panel, so the bar can select it. */
    var onColorTapped: ((EditableColor) -> Unit)? = null

    /** POSITION mode: fired whenever the panel's offset changes from a drag (or a reset), so the
     *  floating bar's X/Y coordinate fields can track the panel live. */
    var onOffsetChanged: ((PanelOffset) -> Unit)? = null

    /** Synthetic per-stream levels (0..1) so the demo sliders sit at pleasant fills without ever
     *  touching the device's real volume while editing. */
    val streamLevels: MutableMap<Int, Float> = mutableMapOf(
        AudioManager.STREAM_MUSIC to 0.6f,
        AudioManager.STREAM_VOICE_CALL to 0.5f,
        AudioManager.STREAM_RING to 0.7f,
        AudioManager.STREAM_NOTIFICATION to 0.6f,
        AudioManager.STREAM_ALARM to 0.4f,
    )

    /** Synthetic ringer mode (kept normal so the demo shows the full, un-muted layout). */
    var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL

    /** This component's offset for the orientation being edited. */
    fun offset(): PanelOffset = working.component(component).offset(orientation)

    /** Replace this component's offset for the orientation being edited (returns the new working copy). */
    fun withOffset(offset: PanelOffset): VersionCustomization {
        val comp = working.component(component)
        working = working.withComponent(component, comp.withOffset(orientation, offset))
        return working
    }

    /** This component's colour overrides. */
    fun colors(): PanelColors = working.component(component).colors

    /** Set one editable colour on this component (null clears the override); returns the new working copy. */
    fun withColor(which: EditableColor, value: Int?): VersionCustomization {
        val comp = working.component(component)
        working = working.withComponent(component, comp.copy(colors = comp.colors.set(which, value)))
        return working
    }
}
