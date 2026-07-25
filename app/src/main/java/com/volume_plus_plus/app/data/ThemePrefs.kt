package com.volume_plus_plus.app.data

import android.content.Context
import com.volume_plus_plus.app.ui.theme.ThemeMode

/**
 * Remembers the user's light/dark/auto choice. Defaults to [ThemeMode.SYSTEM] until the user picks
 * one, so a fresh install follows the device setting. Read once at launch to seed the theme state.
 */
class ThemePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
    }
}
