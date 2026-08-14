package com.volume_plus_plus.app.ui.theme

/**
 * How the app UI picks between the light and dark colour schemes. The set offered in the picker is
 * [com.volume_plus_plus.app.config.AppConfig.SUPPORTED_THEMES]; display names come from
 * [com.volume_plus_plus.app.i18n.label].
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    /** Follow the device's light/dark setting. */
    SYSTEM,
}
