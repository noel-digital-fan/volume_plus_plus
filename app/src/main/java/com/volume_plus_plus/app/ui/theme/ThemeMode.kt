package com.volume_plus_plus.app.ui.theme

/** How the app UI picks between the light and dark colour schemes. */
enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    /** Follow the device's light/dark setting. */
    SYSTEM("System default"),
}
