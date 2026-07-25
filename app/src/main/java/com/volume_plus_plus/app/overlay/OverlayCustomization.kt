package com.volume_plus_plus.app.overlay

/**
 * The independent per-Android-version customization model. Each [OverlayVersion] (7–15) owns its own
 * [VersionCustomization]; nothing here is shared between versions, so editing one style never affects
 * another.
 *
 * A version has one or two [PanelComponent]s (Android 9+ adds the expanded sheet). Each component
 * carries its own colours and — separately for [EditOrientation.PORTRAIT] and
 * [EditOrientation.LANDSCAPE] — its own position offset. Position is the only thing that differs
 * between the two orientations; colours are shared across them.
 */

/**
 * The editable UI components. [EXPANDED] applies only to Android 9+ (see [OverlayVersion]); [OUTPUT]
 * is the Android 15 media-output picker ("This phone") that layers over the expanded sheet — a
 * separate editing surface with its own colours, offered only for the Android 15 style.
 */
enum class PanelComponent(val label: String) {
    MAIN("Main panel"),
    EXPANDED("Expanded panel"),
    OUTPUT("Media output"),
}

/** Which orientation's position is being edited/applied. Position is per-orientation; colours aren't. */
enum class EditOrientation(val label: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}

/** A panel's position nudge from its default docked spot, in dp. +x = right, +y = down. */
data class PanelOffset(val dxDp: Float = 0f, val dyDp: Float = 0f)

/**
 * Per-component colour overrides. Each is an opaque ARGB int, or null to keep the skin's own default
 * for that element. Applied to an [OverlayStyle] via [applyColors].
 *
 * [mediaIcon] and [modeIcon] are the two icon overrides the Android 12–15 **main** panel exposes on
 * top of the general [icon] tint: the music note that rides on the media slider, and the icon of the
 * currently-selected ringer/volume mode. Both sit *inside* a filled control, so without an override
 * they're auto-contrasted against their backing rather than following [icon] — the override lets the
 * user pin them to an exact colour. Null everywhere else (they're only offered for that one panel).
 */
data class PanelColors(
    val container: Int? = null,
    val fill: Int? = null,
    val track: Int? = null,
    val icon: Int? = null,
    val accent: Int? = null,
    val text: Int? = null,
    val secondary: Int? = null,
    val mediaIcon: Int? = null,
    val modeIcon: Int? = null,
    // The Android 12–14 main panel's three-dot overflow button, independent of the general accent.
    val overflow: Int? = null,
    // The trailing volume dot on each Android 15 filled-pill row.
    val dot: Int? = null,
    // The Android 15 "Audio will play on / This phone" output card's surface, independent of the
    // DONE pill's own [secondary] surface.
    val outputSurface: Int? = null,
    // The DONE pill's fill and its text, split out so each is editable on its own.
    val doneBg: Int? = null,
    val doneText: Int? = null,
    // The Android 9–14 expanded sheet's Volume/Sound title, independent of the body [text].
    val title: Int? = null,
)

/** One component's full customization: a position per orientation, and one shared colour set. */
data class ComponentCustomization(
    val portrait: PanelOffset = PanelOffset(),
    val landscape: PanelOffset = PanelOffset(),
    val colors: PanelColors = PanelColors(),
) {
    fun offset(orientation: EditOrientation): PanelOffset =
        if (orientation == EditOrientation.LANDSCAPE) landscape else portrait

    fun withOffset(orientation: EditOrientation, offset: PanelOffset): ComponentCustomization =
        if (orientation == EditOrientation.LANDSCAPE) copy(landscape = offset) else copy(portrait = offset)
}

/**
 * A whole version's customization: its main panel, (Android 9+) its expanded sheet, and (Android 15)
 * its media-output picker. Each is an independent [ComponentCustomization].
 */
data class VersionCustomization(
    val main: ComponentCustomization = ComponentCustomization(),
    val expanded: ComponentCustomization = ComponentCustomization(),
    val output: ComponentCustomization = ComponentCustomization(),
) {
    fun component(c: PanelComponent): ComponentCustomization = when (c) {
        PanelComponent.MAIN -> main
        PanelComponent.EXPANDED -> expanded
        PanelComponent.OUTPUT -> output
    }

    fun withComponent(c: PanelComponent, value: ComponentCustomization): VersionCustomization = when (c) {
        PanelComponent.MAIN -> copy(main = value)
        PanelComponent.EXPANDED -> copy(expanded = value)
        PanelComponent.OUTPUT -> copy(output = value)
    }
}

/**
 * The named overlay elements the colour editor can expose. Not every element is offered on every
 * skin — [visibleColors] picks the subset that actually has a visible effect for a given version +
 * component. The first five ([BACKGROUND]…[ACCENT]) are the common core; [TEXT] and [SECONDARY] only
 * matter where the panel actually draws text / a secondary surface; [MEDIA_ICON] and [MODE_ICON] are
 * the Android 12–15 main panel's two in-fill icon overrides.
 */
enum class EditableColor(val label: String) {
    BACKGROUND("Background"),
    PROGRESS("Slider / progress"),
    TRACK("Slider track"),
    ICON("Icons"),
    ACCENT("Accent / buttons"),
    TEXT("Text"),
    // The secondary surface: the Android 9–11 tune/expand button, the Material You DONE pill, and the
    // Android 15 output/connect cards all sit on this.
    SECONDARY("Secondary surface"),
    // The music note that rides on the media slider (Android 12–15 main panel).
    MEDIA_ICON("Media note icon"),
    // The icon of the currently-selected ringer/volume mode (Android 12–15 main panel).
    MODE_ICON("Active mode icon"),
    // The Android 12–14 main panel's three-dot overflow button (its own colour, not the accent).
    OVERFLOW("Three-dot button"),
    // The trailing volume dot on each Android 15 filled-pill row / the output-picker pill.
    DOT("Volume dot"),
    // The Android 15 "Audio will play on" output card surface (separate from the DONE pill).
    OUTPUT_SURFACE("Output card"),
    // The DONE pill, split into its fill and its text so each is editable independently.
    DONE_BG("Done button"),
    DONE_TEXT("Done text"),
    // The Android 9–14 expanded sheet's Volume/Sound title (its own colour, not the body text).
    TITLE("Title"),
    // The Android 15 "Media output" picker's own, exclusive palette. Each aliases the OUTPUT
    // component's PanelColors field shown in the comment, so nothing here is shared with the Expanded
    // sheet's swatches even though the underlying storage fields are reused per-component.
    OUTPUT_CARD("Card background"),        // -> container
    OUTPUT_SLIDER("Slider fill"),          // -> fill
    OUTPUT_SLIDER_TRACK("Slider track"),   // -> track
    OUTPUT_PICKER_ICON("Icon"),            // -> icon
    OUTPUT_PICKER_TEXT("Text"),            // -> text
    OUTPUT_PICKER_DOT("Volume dot"),       // -> dot
    OUTPUT_CONNECT("Connect surface"),     // -> secondary
    OUTPUT_DONE("Done button"),            // -> doneBg
    OUTPUT_DONE_TEXT("Done text"),         // -> doneText
}

/** The Android 15 "Media output" picker's reference-blue palette (matches the OS art). Shared by the
 *  picker renderer ([OverlayController.buildOutputPickerCardInner]) and the swatch defaults
 *  ([OverlayStyle.defaultColor]) so both seed from the same colours. These are the *light-theme* art;
 *  the dark theme uses the DARK constants below. */
const val OUTPUT_PICKER_REF_TRACK = 0xFFDAE2FF.toInt()
const val OUTPUT_PICKER_REF_FILL = 0xFFB2C5FF.toInt()
const val OUTPUT_PICKER_REF_CONTENT = 0xFF182E60.toInt()
/** The picker's light-theme card surface, "Connect a device" surface, and its own DONE fill/text —
 *  bespoke light art, not the sheet's generic surfaces. */
const val OUTPUT_PICKER_REF_CARD = 0xFFE3E2E9.toInt()
const val OUTPUT_PICKER_REF_CONNECT = 0xFFEEF0FF.toInt()
const val OUTPUT_PICKER_REF_DONE_BG = 0xFF495D92.toInt()
const val OUTPUT_PICKER_REF_DONE_TEXT = 0xFFF1F0F7.toInt()

/**
 * The picker's dark-theme palette — a bespoke dark skin, not a tint of the light art: deep-navy
 * surfaces (slider fill/track) under a light-blue content colour ([_CONTENT_DARK]) shared by the "This
 * phone" label, the drag percentage, the media/"+" icons, the trailing dot and the "Connect a device"
 * text. The DONE pill also gets its own fill/text here rather than borrowing the sheet's secondary
 * surface. Consumed by [OverlayController.buildOutputPickerCard]/[buildOutputPickerCardInner] and the
 * dark branch of [OverlayStyle.defaultColor], so the rendered picker and its swatch seeds agree.
 */
const val OUTPUT_PICKER_REF_TRACK_DARK = 0xFF2B2E42.toInt()
const val OUTPUT_PICKER_REF_FILL_DARK = 0xFF595D71.toInt()
const val OUTPUT_PICKER_REF_CONTENT_DARK = 0xFFDAE2FF.toInt()
const val OUTPUT_PICKER_REF_DONE_BG_DARK = 0xFF96AAE4.toInt()
const val OUTPUT_PICKER_REF_DONE_TEXT_DARK = 0xFF2A3042.toInt()
/** The "Connect a device" card surface in the picker's dark theme (its own, deeper than the sheet). */
const val OUTPUT_PICKER_REF_CONNECT_DARK = 0xFF2E2E38.toInt()

/**
 * The Android 15 expanded "Sound & vibration" sheet's own dark-theme palette — a bespoke dark skin
 * that matches the media-output picker (light-blue controls over deep surfaces), independent of the
 * shared Android 13–15 skin colours (so the main panel and the 13/14 sheet are untouched). Applied by
 * [withAndroid15SheetDark] when that sheet is rendered/seeded in dark theme.
 *
 * - [A15_SHEET_FILL_DARK]: the pill slider fill, the DONE pill fill and the SETTINGS pill outline.
 * - [A15_SHEET_TRACK_DARK]: the pill slider track (its unfilled background).
 * - [A15_SHEET_ON_FILL_DARK]: pill label/icon/dot over the fill, and the DONE pill's label.
 * - [A15_SHEET_ON_TRACK_DARK]: the pill label over the bare track (a low volume level).
 * - [A15_SHEET_OUTPUT_SURFACE_DARK]: the "Audio will play on / This phone" card surface.
 * - [A15_SHEET_CAPTION_DARK]: the card's "Audio will play on" caption and phone icon.
 * - [A15_SHEET_LABEL_DARK]: the card's device label ("This phone") and the SETTINGS pill text.
 */
const val A15_SHEET_FILL_DARK = 0xFFB2C5FF.toInt()
const val A15_SHEET_TRACK_DARK = 0xFF424659.toInt()
const val A15_SHEET_ON_FILL_DARK = 0xFF182E60.toInt()
const val A15_SHEET_ON_TRACK_DARK = 0xFFDDE2F9.toInt()
const val A15_SHEET_OUTPUT_SURFACE_DARK = 0xFF151515.toInt()
const val A15_SHEET_CAPTION_DARK = 0xFFC5C6D0.toInt()
const val A15_SHEET_LABEL_DARK = 0xFFE3E2E9.toInt()
/** The sheet's own background surface (the panel behind the output card, sliders and footer). */
const val A15_SHEET_BG_DARK = 0xFF1C1C27.toInt()

/**
 * The Android 15 sheet's light-theme counterpart to the DARK palette above — the same roles, tuned
 * for a light surface (a dark-blue control over a pale track, dark labels). Applied by
 * [withAndroid15SheetLight] when the sheet is rendered/seeded in light theme.
 *
 * - [A15_SHEET_FILL_LIGHT]: the pill slider fill, the DONE pill fill, the SETTINGS pill outline and
 *   the trailing "·" dot (pinned to the fill colour here, unlike the dark sheet's content-following dot).
 * - [A15_SHEET_TRACK_LIGHT]: the pill slider track (its unfilled background).
 * - [A15_SHEET_ON_FILL_LIGHT]: pill label/icon over the fill, and the DONE pill's label.
 * - [A15_SHEET_ON_TRACK_LIGHT]: the pill label over the bare track (a low volume level).
 * - [A15_SHEET_OUTPUT_SURFACE_LIGHT]: the "Audio will play on / This phone" card surface.
 * - [A15_SHEET_CAPTION_LIGHT]: the card's "Audio will play on" caption and phone icon.
 * - [A15_SHEET_LABEL_LIGHT]: the card's device label ("This phone") and the SETTINGS pill text.
 */
const val A15_SHEET_FILL_LIGHT = 0xFF485D92.toInt()
const val A15_SHEET_TRACK_LIGHT = 0xFFDDE2F9.toInt()
const val A15_SHEET_ON_FILL_LIGHT = 0xFFFFFFFF.toInt()
const val A15_SHEET_ON_TRACK_LIGHT = 0xFF414659.toInt()
const val A15_SHEET_OUTPUT_SURFACE_LIGHT = 0xFFFAF8FF.toInt()
const val A15_SHEET_CAPTION_LIGHT = 0xFF45464F.toInt()
const val A15_SHEET_LABEL_LIGHT = 0xFF1A1B21.toInt()
const val A15_SHEET_BG_LIGHT = 0xFFEEEDF4.toInt()

/**
 * Overlay the Android 15 sheet's dark reference palette onto this base skin style. Used as the base
 * *before* user overrides ([applyColors]) when rendering that sheet in dark theme — so an untouched
 * sheet shows this palette, while any swatch the user pins still wins — and to seed its dark swatches
 * (see [OverlayStyle.defaultColor]) so the editor chips match what's drawn. The derived content colours
 * ([pillContentOnFill]/[pillContentOnTrack]) and the [settingsBorderColor] have no swatch of their own;
 * they're fixed reference values for this sheet.
 */
fun OverlayStyle.withAndroid15SheetDark(): OverlayStyle = copy(
    containerColor = A15_SHEET_BG_DARK,
    fillColor = A15_SHEET_FILL_DARK,
    thumbColor = A15_SHEET_FILL_DARK,
    trackColor = A15_SHEET_TRACK_DARK,
    iconTint = A15_SHEET_CAPTION_DARK,
    textColor = A15_SHEET_LABEL_DARK,
    // The SETTINGS pill's label reads the accent, so pin the accent here rather than adding a field.
    accentColor = A15_SHEET_LABEL_DARK,
    outputSurfaceColor = A15_SHEET_OUTPUT_SURFACE_DARK,
    doneBgColor = A15_SHEET_FILL_DARK,
    doneTextColor = A15_SHEET_ON_FILL_DARK,
    pillContentOnFill = A15_SHEET_ON_FILL_DARK,
    pillContentOnTrack = A15_SHEET_ON_TRACK_DARK,
    settingsBorderColor = A15_SHEET_FILL_DARK,
)

/**
 * The light-theme counterpart of [withAndroid15SheetDark]: overlays the Android 15 sheet's light
 * reference palette onto this base skin style, used both as the render base before user overrides and
 * to seed the light swatches (see [OverlayStyle.defaultColor]). Unlike the dark sheet, the trailing
 * "·" dot is pinned to the fill colour ([dotColor]) rather than following the pill content.
 */
fun OverlayStyle.withAndroid15SheetLight(): OverlayStyle = copy(
    containerColor = A15_SHEET_BG_LIGHT,
    fillColor = A15_SHEET_FILL_LIGHT,
    thumbColor = A15_SHEET_FILL_LIGHT,
    trackColor = A15_SHEET_TRACK_LIGHT,
    iconTint = A15_SHEET_CAPTION_LIGHT,
    textColor = A15_SHEET_LABEL_LIGHT,
    accentColor = A15_SHEET_LABEL_LIGHT,
    outputSurfaceColor = A15_SHEET_OUTPUT_SURFACE_LIGHT,
    doneBgColor = A15_SHEET_FILL_LIGHT,
    doneTextColor = A15_SHEET_ON_FILL_LIGHT,
    pillContentOnFill = A15_SHEET_ON_FILL_LIGHT,
    pillContentOnTrack = A15_SHEET_ON_TRACK_LIGHT,
    settingsBorderColor = A15_SHEET_FILL_LIGHT,
    dotColor = A15_SHEET_FILL_LIGHT,
)

/** The current override for [c] (null = using the skin default). */
fun PanelColors.get(c: EditableColor): Int? = when (c) {
    EditableColor.BACKGROUND -> container
    EditableColor.PROGRESS -> fill
    EditableColor.TRACK -> track
    EditableColor.ICON -> icon
    EditableColor.ACCENT -> accent
    EditableColor.TEXT -> text
    EditableColor.SECONDARY -> secondary
    EditableColor.MEDIA_ICON -> mediaIcon
    EditableColor.MODE_ICON -> modeIcon
    EditableColor.OVERFLOW -> overflow
    EditableColor.DOT -> dot
    EditableColor.OUTPUT_SURFACE -> outputSurface
    EditableColor.DONE_BG -> doneBg
    EditableColor.DONE_TEXT -> doneText
    EditableColor.TITLE -> title
    // The Media-output picker's exclusive swatches alias the OUTPUT component's own colour fields.
    EditableColor.OUTPUT_CARD -> container
    EditableColor.OUTPUT_SLIDER -> fill
    EditableColor.OUTPUT_SLIDER_TRACK -> track
    EditableColor.OUTPUT_PICKER_ICON -> icon
    EditableColor.OUTPUT_PICKER_TEXT -> text
    EditableColor.OUTPUT_PICKER_DOT -> dot
    EditableColor.OUTPUT_CONNECT -> secondary
    EditableColor.OUTPUT_DONE -> doneBg
    EditableColor.OUTPUT_DONE_TEXT -> doneText
}

/** A copy of these colours with [c] set to [value] (null clears the override). */
fun PanelColors.set(c: EditableColor, value: Int?): PanelColors = when (c) {
    EditableColor.BACKGROUND -> copy(container = value)
    EditableColor.PROGRESS -> copy(fill = value)
    EditableColor.TRACK -> copy(track = value)
    EditableColor.ICON -> copy(icon = value)
    EditableColor.ACCENT -> copy(accent = value)
    EditableColor.TEXT -> copy(text = value)
    EditableColor.SECONDARY -> copy(secondary = value)
    EditableColor.MEDIA_ICON -> copy(mediaIcon = value)
    EditableColor.MODE_ICON -> copy(modeIcon = value)
    EditableColor.OVERFLOW -> copy(overflow = value)
    EditableColor.DOT -> copy(dot = value)
    EditableColor.OUTPUT_SURFACE -> copy(outputSurface = value)
    EditableColor.DONE_BG -> copy(doneBg = value)
    EditableColor.DONE_TEXT -> copy(doneText = value)
    EditableColor.TITLE -> copy(title = value)
    EditableColor.OUTPUT_CARD -> copy(container = value)
    EditableColor.OUTPUT_SLIDER -> copy(fill = value)
    EditableColor.OUTPUT_SLIDER_TRACK -> copy(track = value)
    EditableColor.OUTPUT_PICKER_ICON -> copy(icon = value)
    EditableColor.OUTPUT_PICKER_TEXT -> copy(text = value)
    EditableColor.OUTPUT_PICKER_DOT -> copy(dot = value)
    EditableColor.OUTPUT_CONNECT -> copy(secondary = value)
    EditableColor.OUTPUT_DONE -> copy(doneBg = value)
    EditableColor.OUTPUT_DONE_TEXT -> copy(doneText = value)
}

/**
 * This skin's own default for [c] — used to seed a swatch before the user overrides it. The two
 * in-fill icons ([MEDIA_ICON]/[MODE_ICON]) have no single fixed default (they're auto-contrasted
 * against their backing per skin), so their swatch is seeded from the icon tint as a neutral start.
 *
 * [dark] selects the Media-output picker's dark vs light reference palette for the OUTPUT_* swatches
 * (its fill/track/content/dot/icon and the DONE pill), matching what the renderer draws in that theme.
 * Every other swatch already reads a theme-resolved field off this (already dark/light) [OverlayStyle],
 * so [dark] only steers the picker's bespoke, non-style-derived colours.
 */
fun OverlayStyle.defaultColor(c: EditableColor, dark: Boolean = false): Int = when (c) {
    EditableColor.BACKGROUND -> containerColor
    EditableColor.PROGRESS -> fillColor
    EditableColor.TRACK -> trackColor
    EditableColor.ICON -> iconTint
    EditableColor.ACCENT -> accentColor
    EditableColor.TEXT -> textColor
    EditableColor.SECONDARY -> secondaryContainerColor
    EditableColor.MEDIA_ICON -> mediaIconColor ?: iconTint
    EditableColor.MODE_ICON -> modeIconColor ?: iconTint
    // These seed their swatch from the colour they previously shared (before being split out).
    EditableColor.OVERFLOW -> overflowColor ?: accentColor
    EditableColor.DOT -> dotColor ?: textColor
    EditableColor.OUTPUT_SURFACE -> outputSurfaceColor ?: secondaryContainerColor
    EditableColor.DONE_BG -> doneBgColor ?: secondaryContainerColor
    EditableColor.DONE_TEXT -> doneTextColor ?: textColor
    EditableColor.TITLE -> titleColor ?: textColor
    // The Media-output picker seeds its swatches from its reference palette (fill/track/content) and
    // the skin's own surfaces, matching the picker's rendered defaults — the dark theme swaps in the
    // picker's own dark reference art (see the OUTPUT_PICKER_REF_*_DARK constants).
    EditableColor.OUTPUT_CARD -> if (dark) containerColor else OUTPUT_PICKER_REF_CARD
    EditableColor.OUTPUT_SLIDER -> if (dark) OUTPUT_PICKER_REF_FILL_DARK else OUTPUT_PICKER_REF_FILL
    EditableColor.OUTPUT_SLIDER_TRACK -> if (dark) OUTPUT_PICKER_REF_TRACK_DARK else OUTPUT_PICKER_REF_TRACK
    // The "+" connect icon defaults to the picker's content colour in both themes (matching the
    // label/percentage/dot): light-blue in dark theme, dark-navy in light theme.
    EditableColor.OUTPUT_PICKER_ICON -> if (dark) OUTPUT_PICKER_REF_CONTENT_DARK else OUTPUT_PICKER_REF_CONTENT
    EditableColor.OUTPUT_PICKER_TEXT -> if (dark) OUTPUT_PICKER_REF_CONTENT_DARK else OUTPUT_PICKER_REF_CONTENT
    EditableColor.OUTPUT_PICKER_DOT -> if (dark) OUTPUT_PICKER_REF_CONTENT_DARK else OUTPUT_PICKER_REF_CONTENT
    EditableColor.OUTPUT_CONNECT -> if (dark) OUTPUT_PICKER_REF_CONNECT_DARK else OUTPUT_PICKER_REF_CONNECT
    // The DONE pill has its own fill/text in both themes (not the sheet's secondary surface / body text).
    EditableColor.OUTPUT_DONE -> if (dark) OUTPUT_PICKER_REF_DONE_BG_DARK else OUTPUT_PICKER_REF_DONE_BG
    EditableColor.OUTPUT_DONE_TEXT -> if (dark) OUTPUT_PICKER_REF_DONE_TEXT_DARK else OUTPUT_PICKER_REF_DONE_TEXT
}

/**
 * The colours actually exposed for [version] + [component] — only those with a visible effect on that
 * particular panel, so the editor never offers a swatch that changes nothing. The set is tailored per
 * skin (and, for Android 9+, per main/expanded component):
 *
 * - **Android 7–8 main** (its only component): just the core four — background, slider/progress,
 *   track and icons. The top-sheet panel draws no accent/secondary surface, and its labels track the
 *   icon colour, so nothing else there is independently editable.
 * - **Android 9–11 main** (collapsed right-edge control): background, slider, track, icons, plus the
 *   secondary surface (the tune footer). It draws no text and no separate accent.
 * - **Android 9–11 expanded** ("Sound"/"Volume" sheet): background, slider, track, accent (SEE MORE /
 *   DONE), body text, and the sheet title (its own colour). Android 11 also exposes icons; 9 and 10
 *   hide the row icons, so [ICON] is dropped for them. No secondary surface is used on this sheet.
 * - **Android 12–15 main**: the core five (background, slider, track, icons, accent), the two in-fill
 *   icon overrides (media note + active mode icon) and the three-dot overflow button.
 * - **Android 12–14 expanded** (Sound / Sound & vibration sheet): background, slider, track, icons,
 *   accent, text, the sheet title, the secondary surface, and the DONE pill split into fill and text.
 * - **Android 15 expanded** (redesigned Sound & vibration sheet): background, slider, track, icons,
 *   accent, text, the "Audio will play on" output card, the trailing volume dot, and the split DONE
 *   pill (this sheet draws no title). The output-picker modal is edited on its own — see [OUTPUT].
 * - **Android 15 output** (the "Media output" / "This phone" picker): its own exclusive palette —
 *   card background, slider fill/track, icon, text, trailing dot, connect surface and the DONE pill —
 *   sharing no swatch identity with the Expanded sheet (see the OUTPUT_* [EditableColor]s).
 */
fun visibleColors(version: OverlayVersion, component: PanelComponent): List<EditableColor> {
    val main = component == PanelComponent.MAIN
    val core = listOf(
        EditableColor.BACKGROUND,
        EditableColor.PROGRESS,
        EditableColor.TRACK,
        EditableColor.ICON,
    )
    return when (version.skin) {
        // Android 7–8: single top sheet — only the first four colours are used.
        OverlaySkin.ANDROID_7_8 -> core

        OverlaySkin.ANDROID_9_11 ->
            if (main) {
                core + EditableColor.SECONDARY
            } else {
                // Android 9 and 10 hide the sheet's row icons entirely, so [ICON] would change nothing
                // there — drop it. Android 11 keeps the full core. Both add the accent, body text and
                // the independently-editable title.
                val is910 = version.legacyVersion == LegacyVersion.ANDROID_9 ||
                    version.legacyVersion == LegacyVersion.ANDROID_10
                val base = if (is910)
                    listOf(EditableColor.BACKGROUND, EditableColor.PROGRESS, EditableColor.TRACK)
                else core
                base + EditableColor.ACCENT + EditableColor.TEXT + EditableColor.TITLE
            }

        // Android 12: same main-panel controls as 13–15, but the expanded "Sound" sheet uses plain
        // text buttons (SEE MORE / DONE, in the accent) and no filled DONE pill — so no DONE fill/text
        // split there, just the accent, text, secondary surface and title.
        OverlaySkin.MATERIAL_YOU -> when (component) {
            PanelComponent.MAIN ->
                core + EditableColor.ACCENT + EditableColor.MEDIA_ICON + EditableColor.MODE_ICON +
                    EditableColor.OVERFLOW
            else ->
                core + EditableColor.ACCENT + EditableColor.TEXT + EditableColor.SECONDARY +
                    EditableColor.TITLE
        }

        OverlaySkin.ANDROID_13_14 -> when (component) {
            PanelComponent.MAIN ->
                core + EditableColor.ACCENT + EditableColor.MEDIA_ICON + EditableColor.MODE_ICON +
                    EditableColor.OVERFLOW
            PanelComponent.EXPANDED ->
                if (version.iconSet == IconSet.ANDROID_15)
                    // The redesigned Android 15 sheet: the "Audio will play on" output card, the
                    // trailing volume dot, and the split DONE pill (fill + text) — no generic
                    // secondary surface (its only secondary surface is the output card), and no title
                    // (this sheet draws none).
                    core + EditableColor.ACCENT + EditableColor.TEXT + EditableColor.OUTPUT_SURFACE +
                        EditableColor.DOT + EditableColor.DONE_BG + EditableColor.DONE_TEXT
                else
                    // Android 13/14 "Sound & vibration" sheet: filled DONE pill (fill + text split),
                    // plus the independently-editable title.
                    core + EditableColor.ACCENT + EditableColor.TEXT + EditableColor.SECONDARY +
                        EditableColor.DONE_BG + EditableColor.DONE_TEXT + EditableColor.TITLE
            // The Android 15 "Media output" picker: its own exclusive palette (card, slider fill/track,
            // icon, text, trailing dot, connect surface and the DONE pill) — sharing no swatch identity
            // with the Expanded sheet above.
            PanelComponent.OUTPUT ->
                listOf(
                    EditableColor.OUTPUT_CARD,
                    EditableColor.OUTPUT_SLIDER,
                    EditableColor.OUTPUT_SLIDER_TRACK,
                    EditableColor.OUTPUT_PICKER_ICON,
                    EditableColor.OUTPUT_PICKER_TEXT,
                    EditableColor.OUTPUT_PICKER_DOT,
                    EditableColor.OUTPUT_CONNECT,
                    EditableColor.OUTPUT_DONE,
                    EditableColor.OUTPUT_DONE_TEXT,
                )
        }
    }
}

/** Layer [colors] onto this style, keeping each element's default where no override is set. */
fun OverlayStyle.applyColors(colors: PanelColors): OverlayStyle = copy(
    trackColor = colors.track ?: trackColor,
    fillColor = colors.fill ?: fillColor,
    // The fill override drives the thumb too, so slider fill and its handle recolour together.
    thumbColor = colors.fill ?: thumbColor,
    containerColor = colors.container ?: containerColor,
    iconTint = colors.icon ?: iconTint,
    accentColor = colors.accent ?: accentColor,
    textColor = colors.text ?: textColor,
    secondaryContainerColor = colors.secondary ?: secondaryContainerColor,
    // The two in-fill icons override their auto-contrast default only when the user sets them; left
    // null the renderer falls back to contrasting the backing (see OverlayController).
    mediaIconColor = colors.mediaIcon ?: mediaIconColor,
    modeIconColor = colors.modeIcon ?: modeIconColor,
    // Split-out surfaces: each stays null (renderer falls back to its historic shared colour — accent
    // for the overflow, the on-content colour for the dot, the secondary surface for the output card
    // and DONE fill, the text colour for the DONE label) until the user pins an explicit value.
    overflowColor = colors.overflow ?: overflowColor,
    dotColor = colors.dot ?: dotColor,
    outputSurfaceColor = colors.outputSurface ?: outputSurfaceColor,
    doneBgColor = colors.doneBg ?: doneBgColor,
    doneTextColor = colors.doneText ?: doneTextColor,
    titleColor = colors.title ?: titleColor,
)
