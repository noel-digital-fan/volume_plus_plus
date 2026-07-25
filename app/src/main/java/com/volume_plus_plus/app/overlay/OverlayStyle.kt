package com.volume_plus_plus.app.overlay

import android.graphics.Color

/** The available overlay looks, each an accurate re-creation of a real volume panel. */
enum class OverlaySkin(val displayName: String) {
    MATERIAL_YOU("Android 12"),
    ANDROID_13_14("Android 13–15"),
    ANDROID_9_11("Android 9–11"),
    ANDROID_7_8("Android 7–8"),
}

/**
 * For the Android 13–15 skin, which OS revision's volume-panel presentation to mimic:
 * - [ANDROID_13]: the classic bell + a single combined "Ring & notification" control.
 * - [ANDROID_14]: the notification stream gets its own slider (split from Ring) with a
 *   megaphone/"sound" ringer icon instead of the bell.
 * - [ANDROID_15]: same main panel as 14, but a redesigned "Sound & vibration" sheet — an
 *   "Audio will play on / This phone" output selector on top, filled-pill sliders (icon + label
 *   inside, a trailing dot) for every stream, and a media-output picker that shows the volume as a
 *   percentage while dragging.
 * Selected per skin from the overlay settings screen.
 */
enum class IconSet(val displayName: String) {
    ANDROID_13("Android 13"),
    ANDROID_14("Android 14"),
    ANDROID_15("Android 15"),
}

/**
 * For the Android 9–11 skin, which specific OS revision's volume panel to mimic. They share the
 * layout but differ in small details: Android 9 opens the sheet with a settings-gear button (10 and
 * 11 use the tune/sliders button), and the sheet is titled "Volume" on 9 and 10 but "Sound" on 11.
 * Selected per skin from the overlay settings screen.
 */
enum class LegacyVersion(val displayName: String) {
    ANDROID_9("Android 9"),
    ANDROID_10("Android 10"),
    ANDROID_11("Android 11"),
}

/**
 * For the Android 7–8 skin, which OS revision's volume panel to mimic. Android 8 keeps the
 * labelled rows with Media on top; Android 7 drops the row labels entirely (icons only) and orders
 * the streams Ring / Media / Alarm. Selected per skin from the overlay settings screen.
 */
enum class SevenEightVersion(val displayName: String) {
    ANDROID_7("Android 7"),
    ANDROID_8("Android 8"),
}

/**
 * Uniform shrink applied to every overlay dimension (dp) so the whole panel renders slightly more
 * compact than 1:1 — tuned to sit a touch smaller than the real system panels.
 */
const val OVERLAY_SCALE = 0.95f

/** Where the panel docks on screen. */
enum class Placement { RIGHT_EDGE, TOP }

/** Which way an individual slider fills. */
enum class SliderOrientation { VERTICAL, HORIZONTAL }

/**
 * How a single slider is drawn:
 * - [FILL_PILL]: a solid rounded bar that fills up to the level (Material You / OEM pills).
 * - [LINE_THUMB]: a thin track with a round thumb, icon at the leading edge (Android 7–11 rows).
 */
enum class SliderRender {
    FILL_PILL,
    LINE_THUMB,
    /**
     * A thin vertical track with a wide rounded capsule that grows from the bottom to the level, the
     * icon carried inside the capsule (the Android 12 / Material You media slider).
     */
    CAPSULE,
    /**
     * A horizontal filled pill that grows start→end, carrying a leading icon + label *inside* the
     * fill (the label flips to sit on the track, in the on-surface colour, when the fill is too short
     * to contain it) and a small trailing dot — the Android 15 "Sound & vibration" sheet rows.
     */
    PILL_LABEL,
}

/**
 * Data-driven visual spec for the overlay. [OverlayController] and [VolumeSlider] render entirely
 * from these values, so a new look is just a new [OverlayStyle] in [styleFor].
 *
 * Lengths are in dp. [pillCornerFraction] is the corner radius as a fraction of the pill thickness
 * (0.5 = fully rounded, lower = squarer). [stretch] makes each slider fill the container's cross
 * axis (full-width rows for a top sheet). Colors are ARGB ints; [thumbColor] is only used by
 * [SliderRender.LINE_THUMB].
 *
 * [accentColor], [secondaryContainerColor] and [textColor] are used by the richer custom layouts
 * (currently Android 9–11): the tinted controls/text-buttons, a secondary surface (e.g. the tune
 * footer), and primary text (sheet title / row labels) respectively.
 *
 * [inactiveColor] and [pressedHaloColor] back the [SliderRender.LINE_THUMB] row states (today only
 * the Android 7–8 rows use them): the grey a row's fill/thumb turn while the row is inactive —
 * greyed out because a sibling is being dragged, or locked by "Alarms only" — and the translucent
 * ripple halo drawn behind the thumb of the row that *is* being dragged.
 */
data class OverlayStyle(
    val placement: Placement,
    val orientation: SliderOrientation,
    val render: SliderRender,
    val stretch: Boolean,
    /** Whether the panel shows the full system-stream rows behind an expand/collapse control. */
    val expandable: Boolean,
    /**
     * How the ringer-mode button behaves: `true` opens a small pop-up menu to pick a mode directly
     * (the Android 12 look); `false` cycles normal → vibrate → silent on each tap (Android 9–11).
     */
    val ringerMenu: Boolean,
    val pillLengthDp: Int,
    val pillThicknessDp: Int,
    val pillCornerFraction: Float,
    val spacingDp: Int,
    val containerCornerDp: Int,
    val containerPaddingDp: Int,
    val edgeMarginDp: Int,
    val trackColor: Int,
    val fillColor: Int,
    val thumbColor: Int,
    val containerColor: Int,
    val iconTint: Int,
    val accentColor: Int,
    val secondaryContainerColor: Int,
    val textColor: Int,
    val inactiveColor: Int,
    val pressedHaloColor: Int,
    /**
     * Optional overrides for the two icons that sit *inside* a filled control on the Android 12–15
     * main panel: the music note on the media slider ([mediaIconColor]) and the currently-selected
     * ringer/volume mode icon ([modeIconColor]). Null (the default for every skin in [styleFor]) means
     * "auto-contrast against the backing" — the historic behaviour; a non-null value (layered on by
     * [applyColors] from a user override) pins the icon to that exact colour instead.
     */
    val mediaIconColor: Int? = null,
    val modeIconColor: Int? = null,
    /**
     * Optional overrides for elements that historically shared another colour, split out so each can
     * be recoloured on its own. Null (the default for every skin in [styleFor]) keeps the historic
     * shared source; a non-null value (layered on by [applyColors] from a user override) pins it:
     * - [overflowColor]: the Android 12–15 main panel's three-dot overflow button (was [accentColor]).
     * - [dotColor]: the trailing volume dot on each Android 15 filled pill (was the on-content colour).
     * - [outputSurfaceColor]: the Android 15 "Audio will play on" card (was [secondaryContainerColor]).
     * - [doneBgColor] / [doneTextColor]: the DONE pill's fill / label (were [secondaryContainerColor]
     *   and [textColor]).
     * - [titleColor]: the Android 9–14 expanded sheet's Volume/Sound title (was [textColor]).
     */
    val overflowColor: Int? = null,
    val dotColor: Int? = null,
    val outputSurfaceColor: Int? = null,
    val doneBgColor: Int? = null,
    val doneTextColor: Int? = null,
    val titleColor: Int? = null,
    /**
     * Explicit content colours for a [SliderRender.PILL_LABEL] row's label/icon/dot, split by which
     * side they sit over: [pillContentOnFill] over the filled part, [pillContentOnTrack] over the bare
     * track (a low level). Null (every skin's default in [styleFor]) keeps the historic automatic
     * behaviour — an on-fill colour auto-contrasted against the fill, and [textColor] over the track.
     * The Android 15 dark sheet pins both to its own reference palette (see [withAndroid15SheetDark]);
     * the output picker instead uses its single-colour override, which still wins over these.
     */
    val pillContentOnFill: Int? = null,
    val pillContentOnTrack: Int? = null,
    /**
     * The Material You / Android 15 SETTINGS pill's outline colour, split from the general [iconTint]
     * it historically borrowed. Null (every skin's default in [styleFor]) keeps that behaviour; the
     * Android 15 dark sheet pins it to its own reference blue (see [withAndroid15SheetDark]).
     */
    val settingsBorderColor: Int? = null,
)

/**
 * The concrete per-skin constants, tuned to resemble each reference panel. [dark] selects a
 * light/dark palette for skins that ship both (Android 7–8 and Android 9–11, matching the system).
 */
fun styleFor(skin: OverlaySkin, dark: Boolean = false): OverlayStyle = when (skin) {
    // Android 12+ (Material You): structurally like Android 9–11 — a right-edge collapsed panel
    // (ringer button + vertical media slider + tune footer) that expands into a bottom "Sound"
    // sheet — but with Material 3 shapes/colours (rounder cards, a filled pill slider) and a
    // ringer-mode pop-up menu instead of tap-to-cycle. Ships light + dark.
    OverlaySkin.MATERIAL_YOU -> OverlayStyle(
        placement = Placement.RIGHT_EDGE,
        orientation = SliderOrientation.VERTICAL,
        render = SliderRender.CAPSULE,
        stretch = false,
        expandable = true,
        ringerMenu = true,
        pillLengthDp = 120,
        pillThicknessDp = 40,        // filled-pill width (compact)
        pillCornerFraction = 0.5f,   // fully rounded pill
        spacingDp = 10,
        containerCornerDp = 26,      // Material 3 large corner
        containerPaddingDp = 0,
        edgeMarginDp = 12,
        // Material 3 palette from a blue seed: primary #00668b (light) / #c1e8ff (dark).
        trackColor = if (dark) Color.parseColor("#FF40484D") else Color.parseColor("#FFCFE4F2"),
        fillColor = if (dark) Color.parseColor("#FFC1E8FF") else Color.parseColor("#FF00668B"),
        thumbColor = if (dark) Color.parseColor("#FFC1E8FF") else Color.parseColor("#FF00668B"),
        containerColor = if (dark) Color.parseColor("#FF1A1C1E") else Color.parseColor("#FFFFFFFF"),
        iconTint = if (dark) Color.parseColor("#FFC3C7CF") else Color.parseColor("#FF43474E"),
        accentColor = if (dark) Color.parseColor("#FFC1E8FF") else Color.parseColor("#FF00668B"),
        secondaryContainerColor = if (dark) Color.parseColor("#FF004D68") else Color.parseColor("#FFC5E7FF"),
        textColor = if (dark) Color.parseColor("#FFE2E2E5") else Color.parseColor("#FF191C1E"),
        inactiveColor = if (dark) Color.parseColor("#FFC3C7CF") else Color.parseColor("#FF43474E"),
        pressedHaloColor = if (dark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000"),
    )

    // Android 13/14: identical to Android 12 (same shapes, layout and sheet) except for the primary
    // accent, which shifts to the 13/14 blue: #485d92 (light) / #dae2ff (dark). That accent drives the
    // slider fill/thumb, the ringer + overflow icons and the sheet's SETTINGS/DONE buttons.
    OverlaySkin.ANDROID_13_14 -> OverlayStyle(
        placement = Placement.RIGHT_EDGE,
        orientation = SliderOrientation.VERTICAL,
        render = SliderRender.CAPSULE,
        stretch = false,
        expandable = true,
        ringerMenu = true,
        pillLengthDp = 120,
        pillThicknessDp = 40,
        pillCornerFraction = 0.5f,
        spacingDp = 10,
        containerCornerDp = 26,
        containerPaddingDp = 0,
        edgeMarginDp = 12,
        // The main media-slider background (its track): the Android 15 full-pill slider uses this as
        // its shaped background; Android 13/14 keep it as the thin stick behind the fill capsule.
        trackColor = if (dark) Color.parseColor("#FF44464E") else Color.parseColor("#FFC5C6D0"),
        fillColor = if (dark) Color.parseColor("#FFDAE2FF") else Color.parseColor("#FF485D92"),
        thumbColor = if (dark) Color.parseColor("#FFDAE2FF") else Color.parseColor("#FF485D92"),
        containerColor = if (dark) Color.parseColor("#FF1B1B1F") else Color.parseColor("#FFFEFBFF"),
        iconTint = if (dark) Color.parseColor("#FFC6C6D0") else Color.parseColor("#FF45464F"),
        accentColor = if (dark) Color.parseColor("#FFDAE2FF") else Color.parseColor("#FF485D92"),
        secondaryContainerColor = if (dark) Color.parseColor("#FF3B4664") else Color.parseColor("#FFDCE1F9"),
        textColor = if (dark) Color.parseColor("#FFE3E1E9") else Color.parseColor("#FF1B1B1F"),
        inactiveColor = if (dark) Color.parseColor("#FFC6C6D0") else Color.parseColor("#FF45464F"),
        pressedHaloColor = if (dark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000"),
    )

    // Android 9–11: right-edge collapsed panel (ringer button + vertical media slider + tune
    // footer); tapping tune opens a bottom "Sound" sheet with all streams. Ships light + dark.
    OverlaySkin.ANDROID_9_11 -> OverlayStyle(
        placement = Placement.RIGHT_EDGE,
        orientation = SliderOrientation.VERTICAL,
        render = SliderRender.LINE_THUMB,
        stretch = false,
        expandable = true,
        ringerMenu = false,
        pillLengthDp = 130,
        pillThicknessDp = 24,        // slider cross-axis thickness (line + thumb)
        pillCornerFraction = 0f,
        spacingDp = 10,              // gap between the ringer card and the slider card
        containerCornerDp = 16,      // card corner radius
        containerPaddingDp = 0,      // cards carry their own padding
        edgeMarginDp = 8,
        trackColor = if (dark) Color.parseColor("#FF5F6368") else Color.parseColor("#FFDADCE0"),
        fillColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        thumbColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        containerColor = if (dark) Color.parseColor("#FF2D2D2D") else Color.parseColor("#FFFFFFFF"),
        iconTint = if (dark) Color.parseColor("#FFBDBDBD") else Color.parseColor("#FF5F6368"),
        accentColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        secondaryContainerColor = if (dark) Color.parseColor("#FF3C3C3C") else Color.parseColor("#FFF1F3F4"),
        textColor = if (dark) Color.parseColor("#FFFFFFFF") else Color.parseColor("#FF202124"),
        inactiveColor = if (dark) Color.parseColor("#FFBDBDBD") else Color.parseColor("#FF5F6368"),
        pressedHaloColor = if (dark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000"),
    )

    // Android 7–8: full-width top sheet, horizontal rows with a thin track + round thumb + chevron.
    OverlaySkin.ANDROID_7_8 -> OverlayStyle(
        placement = Placement.TOP,
        orientation = SliderOrientation.HORIZONTAL,
        render = SliderRender.LINE_THUMB,
        stretch = true,
        expandable = true,
        ringerMenu = false,
        pillLengthDp = 0,            // ignored while stretch = true
        pillThicknessDp = 62,        // row height (label above + slider below)
        pillCornerFraction = 0f,
        spacingDp = 6,
        containerCornerDp = 0,       // flush top sheet
        containerPaddingDp = 14,
        edgeMarginDp = 0,
        trackColor = if (dark) Color.parseColor("#4D5B63") else Color.parseColor("#FFD8D8D8"),
        fillColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        thumbColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        containerColor = if (dark) Color.parseColor("#FF263238") else Color.parseColor("#FFFAFAFA"),
        iconTint = if (dark) Color.parseColor("#FFB0BEC5") else Color.parseColor("#FF757575"),
        accentColor = if (dark) Color.parseColor("#FF80CBC4") else Color.parseColor("#FF009688"),
        secondaryContainerColor = if (dark) Color.parseColor("#FF263238") else Color.parseColor("#FFFAFAFA"),
        textColor = if (dark) Color.parseColor("#FFB0BEC5") else Color.parseColor("#FF757575"),
        // Sampled from the reference shots: while one row is used the others' slider turns this
        // blue-grey (dark) / mid-grey (light); the dragged thumb sits in a soft ripple halo.
        inactiveColor = if (dark) Color.parseColor("#FF78909C") else Color.parseColor("#FF9E9E9E"),
        pressedHaloColor = if (dark) Color.parseColor("#26FFFFFF") else Color.parseColor("#1F000000"),
    )
}

/**
 * The first-run default skin — the newest look (Android 13–15), paired with the Android 15 icon set
 * (see [OverlayPrefs.getIconSet]) so a fresh install opens on the Android 15 style regardless of the
 * device's own OS version.
 */
fun defaultSkin(): OverlaySkin = OverlaySkin.ANDROID_13_14
