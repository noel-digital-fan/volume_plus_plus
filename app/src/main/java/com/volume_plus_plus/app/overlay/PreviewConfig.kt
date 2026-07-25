package com.volume_plus_plus.app.overlay

import android.media.AudioManager
import android.widget.FrameLayout

/**
 * Drives an [OverlayController] in embedded-preview mode instead of as a real system overlay. When a
 * controller is constructed with one of these it renders the exact same panel views into [host]
 * (rather than the WindowManager), reads synthetic demo volumes instead of the device's, never
 * auto-hides, and applies the in-progress [customization] — so the editor's live preview shows
 * precisely what a saved change will look like.
 *
 * The fields are plain vars so the editor can retarget the preview (a different version/component/
 * orientation, or freshly-edited colours/positions) and re-render without rebuilding the controller.
 */
class PreviewConfig(val host: FrameLayout) {

    /** Which style is being previewed (fixes the skin + sub-version regardless of the saved pref). */
    var version: OverlayVersion = OverlayVersion.ANDROID_15

    /** Which of the version's components to show — the main panel or the expanded sheet. */
    var component: PanelComponent = PanelComponent.MAIN

    /** Which orientation's position to apply (and which the editor's frame is shaped for). */
    var orientation: EditOrientation = EditOrientation.PORTRAIT

    /** The unsaved, in-progress customization to render with. */
    var customization: VersionCustomization = VersionCustomization()

    /** Synthetic per-stream levels (0..1) so the demo sliders sit at pleasant, non-trivial fills. */
    val streamLevels: MutableMap<Int, Float> = mutableMapOf(
        AudioManager.STREAM_MUSIC to 0.6f,
        AudioManager.STREAM_VOICE_CALL to 0.5f,
        AudioManager.STREAM_RING to 0.7f,
        AudioManager.STREAM_NOTIFICATION to 0.6f,
        AudioManager.STREAM_ALARM to 0.4f,
    )

    /** Synthetic ringer mode (kept normal so the demo shows the full, un-muted layout). */
    var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL
}
