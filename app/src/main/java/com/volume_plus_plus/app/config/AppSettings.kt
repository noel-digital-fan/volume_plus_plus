package com.volume_plus_plus.app.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import com.volume_plus_plus.app.i18n.Language
import com.volume_plus_plus.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The runtime half of the app's configuration: the user's theme and language, persisted, plus the
 * detected system language they can override. The build-time half — version, defaults, supported
 * sets — is [AppConfig].
 *
 * A process-wide singleton on purpose. Both settings are read from places that have no route back to
 * the UI's state (the accessibility service, the WindowManager overlay, the eyedropper's foreground
 * service), so a single observable source beats threading values through every constructor.
 * [init] is called once from `VolumeApp.onCreate`, before any activity or service exists; until then
 * the flows hold [AppConfig]'s defaults, so a read can never be uninitialised — only stale.
 */
object AppSettings {

    private var prefs: SharedPreferences? = null

    private val _themeMode = MutableStateFlow(AppConfig.DEFAULT_THEME)

    /** Light/dark/system, as the user set it. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _languageOverride = MutableStateFlow<Language?>(null)

    /** The language the user picked by hand, or null while the app follows the device. */
    val languageOverride: StateFlow<Language?> = _languageOverride.asStateFlow()

    private val _systemLanguage = MutableStateFlow(AppConfig.FALLBACK_LANGUAGE)

    /** What the device's own locale maps to — [AppConfig.FALLBACK_LANGUAGE] if we ship nothing for it. */
    val systemLanguage: StateFlow<Language> = _systemLanguage.asStateFlow()

    private val _language = MutableStateFlow(AppConfig.FALLBACK_LANGUAGE)

    /** The language actually in effect: the user's override if they set one, else [systemLanguage]. */
    val language: StateFlow<Language> = _language.asStateFlow()

    /** Load the stored settings and detect the device language. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = stored
        _themeMode.value = readThemeMode(stored)
        _languageOverride.value = Language.forCode(stored.getString(KEY_LANGUAGE, null))
        _systemLanguage.value = Language.forLocale(deviceLocale())
        resolveLanguage()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs?.edit()?.putString(KEY_THEME_MODE, mode.name)?.apply()
    }

    /** Pick a language by hand, or pass null to go back to following the device. */
    fun setLanguage(language: Language?) {
        _languageOverride.value = language
        // Store the code rather than the enum name so a renamed constant can't orphan a saved
        // choice — the ISO code is the stable identity, and it's what the picker matches on too.
        prefs?.edit()?.apply {
            if (language == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, language.code)
        }?.apply()
        resolveLanguage()
    }

    /**
     * Re-detect the device language. Called on a configuration change: the user can switch the
     * system language while our process is alive, and services outlive the activity that would
     * otherwise have been recreated for it.
     */
    fun refreshSystemLanguage() {
        _systemLanguage.value = Language.forLocale(deviceLocale())
        resolveLanguage()
    }

    private fun resolveLanguage() {
        _language.value = _languageOverride.value ?: _systemLanguage.value
    }

    private fun readThemeMode(prefs: SharedPreferences): ThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, null) ?: return AppConfig.DEFAULT_THEME
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(AppConfig.DEFAULT_THEME)
    }

    /**
     * The device's own locale. Read from the *system* resources rather than an app context, so it
     * still reports what the user set even if app resources are ever locale-overridden.
     */
    private fun deviceLocale(): Locale {
        val locales = Resources.getSystem().configuration.locales
        return if (locales.isEmpty) Locale.getDefault() else locales[0]
    }

    private const val PREFS = "settings"

    // Unchanged from the ThemePrefs this replaced, so an existing install keeps its theme choice.
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LANGUAGE = "language"
}
