package com.volume_plus_plus.app.overlay

import com.volume_plus_plus.app.data.OverlayPrefs

/**
 * One directly-selectable Android version in the overlay style picker (Android 7–15). Each maps to
 * the underlying [OverlaySkin] plus, where the skin covers several releases, the sub-version pref its
 * skin reads — so picking e.g. "Android 15" both selects the 13–15 skin and sets its icon set.
 *
 * This is the stable identity a per-version customization ([VersionCustomization]) is stored against,
 * so every style (7–15) can be positioned and recoloured independently of the others. Declared
 * newest-first so the picker reads top (newest) → bottom (oldest).
 */
enum class OverlayVersion(
    val label: String,
    val skin: OverlaySkin,
    val iconSet: IconSet? = null,
    val legacyVersion: LegacyVersion? = null,
    val sevenEightVersion: SevenEightVersion? = null,
) {
    ANDROID_15("Android 15", OverlaySkin.ANDROID_13_14, iconSet = IconSet.ANDROID_15),
    ANDROID_14("Android 14", OverlaySkin.ANDROID_13_14, iconSet = IconSet.ANDROID_14),
    ANDROID_13("Android 13", OverlaySkin.ANDROID_13_14, iconSet = IconSet.ANDROID_13),
    ANDROID_12("Android 12", OverlaySkin.MATERIAL_YOU),
    ANDROID_11("Android 11", OverlaySkin.ANDROID_9_11, legacyVersion = LegacyVersion.ANDROID_11),
    ANDROID_10("Android 10", OverlaySkin.ANDROID_9_11, legacyVersion = LegacyVersion.ANDROID_10),
    ANDROID_9("Android 9", OverlaySkin.ANDROID_9_11, legacyVersion = LegacyVersion.ANDROID_9),
    ANDROID_8("Android 8", OverlaySkin.ANDROID_7_8, sevenEightVersion = SevenEightVersion.ANDROID_8),
    ANDROID_7("Android 7", OverlaySkin.ANDROID_7_8, sevenEightVersion = SevenEightVersion.ANDROID_7),
    ;

    /**
     * Whether this version has a separate expanded panel to edit. Android 9+ (every rich-panel skin)
     * has two UI components — the edge-docked main panel and the expanded sheet — that are customised
     * independently; Android 7–8 has a single top sheet, so only [PanelComponent.MAIN] applies.
     */
    val hasExpanded: Boolean get() = skin != OverlaySkin.ANDROID_7_8

    /**
     * Whether this version has the Android 15 media-output picker ("This phone") as a third editable
     * surface. Only the Android 15 style draws it; every other version routes its media output through
     * the main/expanded panels alone.
     */
    val hasOutputPicker: Boolean get() = iconSet == IconSet.ANDROID_15

    /** The components this version exposes for editing (both position and colour). */
    fun components(): List<PanelComponent> = buildList {
        add(PanelComponent.MAIN)
        if (hasExpanded) add(PanelComponent.EXPANDED)
        if (hasOutputPicker) add(PanelComponent.OUTPUT)
    }

    /** Persist this version: the skin, plus the sub-version pref its skin reads (if any). */
    fun apply(prefs: OverlayPrefs) {
        prefs.setSkin(skin)
        iconSet?.let { prefs.setIconSet(it) }
        legacyVersion?.let { prefs.setLegacyVersion(it) }
        sevenEightVersion?.let { prefs.setSevenEightVersion(it) }
    }

    companion object {
        /** The version currently stored in [prefs] — its skin resolved down to a single release. */
        fun current(prefs: OverlayPrefs): OverlayVersion = when (prefs.getSkin()) {
            OverlaySkin.MATERIAL_YOU -> ANDROID_12
            OverlaySkin.ANDROID_13_14 -> when (prefs.getIconSet()) {
                IconSet.ANDROID_13 -> ANDROID_13
                IconSet.ANDROID_14 -> ANDROID_14
                IconSet.ANDROID_15 -> ANDROID_15
            }
            OverlaySkin.ANDROID_9_11 -> when (prefs.getLegacyVersion()) {
                LegacyVersion.ANDROID_9 -> ANDROID_9
                LegacyVersion.ANDROID_10 -> ANDROID_10
                LegacyVersion.ANDROID_11 -> ANDROID_11
            }
            OverlaySkin.ANDROID_7_8 -> when (prefs.getSevenEightVersion()) {
                SevenEightVersion.ANDROID_7 -> ANDROID_7
                SevenEightVersion.ANDROID_8 -> ANDROID_8
            }
        }
    }
}
