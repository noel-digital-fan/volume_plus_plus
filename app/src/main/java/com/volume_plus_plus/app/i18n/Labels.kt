package com.volume_plus_plus.app.i18n

import com.volume_plus_plus.app.overlay.EditOrientation
import com.volume_plus_plus.app.overlay.EditableColor
import com.volume_plus_plus.app.overlay.PanelComponent
import com.volume_plus_plus.app.ui.theme.ThemeMode

/**
 * Display names for the app's enums.
 *
 * Kept out of the enums themselves so translators only ever edit flat key/value lists in a
 * `StringsXx.kt`, never a `when` branch — and so an enum can't quietly carry an English literal that
 * no translation can reach. The mapping is exhaustive on purpose: adding an enum constant fails the
 * build here until it has a name.
 *
 * Two names are deliberately absent because they aren't translatable text:
 * - `OverlayVersion.label` — "Android 15" and friends are the platform's own version numbers.
 * - `AppInfo.label` — an installed app's name, which comes from that app.
 */

fun ThemeMode.label(s: Strings): String = when (this) {
    ThemeMode.LIGHT -> s.themeLight
    ThemeMode.DARK -> s.themeDark
    ThemeMode.SYSTEM -> s.themeSystem
}

/**
 * A language's own name — always its endonym, never translated. A picker entry has to be legible to
 * someone who only reads that language, which is exactly the person looking for it.
 */
fun Language.label(): String = endonym

/** Full component name. [PanelComponent.shortLabel] is the abbreviated form for the editor bar. */
fun PanelComponent.label(s: Strings): String = when (this) {
    PanelComponent.MAIN -> s.panelComponentMain
    PanelComponent.EXPANDED -> s.panelComponentExpanded
    PanelComponent.OUTPUT -> s.panelComponentOutput
}

/** Abbreviated component name, for the cramped live-editor switch chips. */
fun PanelComponent.shortLabel(s: Strings): String = when (this) {
    PanelComponent.MAIN -> s.liveEditComponentMain
    PanelComponent.EXPANDED -> s.liveEditComponentExpanded
    PanelComponent.OUTPUT -> s.liveEditComponentOutput
}

fun EditOrientation.label(s: Strings): String = when (this) {
    EditOrientation.PORTRAIT -> s.orientationPortrait
    EditOrientation.LANDSCAPE -> s.orientationLandscape
}

fun EditableColor.label(s: Strings): String = when (this) {
    EditableColor.BACKGROUND -> s.colourBackground
    EditableColor.PROGRESS -> s.colourProgress
    EditableColor.TRACK -> s.colourTrack
    EditableColor.ICON -> s.colourIcon
    EditableColor.ACCENT -> s.colourAccent
    EditableColor.TEXT -> s.colourText
    EditableColor.SECONDARY -> s.colourSecondary
    EditableColor.MEDIA_ICON -> s.colourMediaIcon
    EditableColor.MODE_ICON -> s.colourModeIcon
    EditableColor.OVERFLOW -> s.colourOverflow
    EditableColor.DOT -> s.colourDot
    EditableColor.OUTPUT_SURFACE -> s.colourOutputSurface
    EditableColor.DONE_BG -> s.colourDoneBg
    EditableColor.DONE_TEXT -> s.colourDoneText
    EditableColor.TITLE -> s.colourTitle
    EditableColor.OUTPUT_CARD -> s.colourOutputCard
    EditableColor.OUTPUT_SLIDER -> s.colourOutputSlider
    EditableColor.OUTPUT_SLIDER_TRACK -> s.colourOutputSliderTrack
    EditableColor.OUTPUT_PICKER_ICON -> s.colourOutputIcon
    EditableColor.OUTPUT_PICKER_TEXT -> s.colourOutputText
    EditableColor.OUTPUT_PICKER_DOT -> s.colourOutputDot
    EditableColor.OUTPUT_CONNECT -> s.colourOutputConnect
    EditableColor.OUTPUT_DONE -> s.colourOutputDone
    EditableColor.OUTPUT_DONE_TEXT -> s.colourOutputDoneText
}
