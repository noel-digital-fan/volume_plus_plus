package com.volume_plus_plus.app.config

import com.volume_plus_plus.app.BuildConfig
import com.volume_plus_plus.app.i18n.Language
import com.volume_plus_plus.app.ui.theme.ThemeMode

/**
 * The app's single, central configuration. Everything that is a *build-time* choice — the app
 * identity, the version, and the defaults the app starts from on a fresh install — lives here, so
 * there is one file to read (and one to edit) rather than constants scattered through the codebase.
 *
 * The matching *runtime* half is [AppSettings], which holds the user's own theme and language
 * choices and persists them. Read defaults from here; read what the user actually picked from there.
 *
 * ### For contributors
 * - **App version** — the numbers themselves live in `app/build.gradle.kts` (`versionCode` /
 *   `versionName`), because the Android build, the APK manifest and the store tooling all read them
 *   from there. [VERSION_NAME] and [VERSION_CODE] re-export them so app code never has to care, and
 *   so there is exactly one place a version can be bumped and no chance of the two drifting apart.
 * - **Theme** — [DEFAULT_THEME] picks what a fresh install uses. Available modes are [ThemeMode].
 * - **Language** — the supported set is the [Language] enum; adding one is two edits and is
 *   documented in `docs/TRANSLATING.md`. [FALLBACK_LANGUAGE] is English and must stay English: it is
 *   what the app falls back to for an unmapped device locale, and (per-key) for anything a
 *   translation hasn't filled in yet.
 */
object AppConfig {

    /** The app's own name. A brand, so it is deliberately never translated. */
    const val APP_NAME: String = "Volume++"

    /** Human-readable version, e.g. `1.1.0`. Bumped in `app/build.gradle.kts`. */
    val VERSION_NAME: String = BuildConfig.VERSION_NAME

    /** Monotonic build number. Bumped in `app/build.gradle.kts`. */
    val VERSION_CODE: Int = BuildConfig.VERSION_CODE

    /**
     * Light/dark scheme used until the user picks one. [ThemeMode.SYSTEM] means a fresh install
     * follows the device setting; set it to [ThemeMode.LIGHT] or [ThemeMode.DARK] to always start
     * on that scheme instead.
     */
    val DEFAULT_THEME: ThemeMode = ThemeMode.SYSTEM

    /**
     * The language used when the device's locale maps to nothing we ship, and the per-key fallback
     * for strings a translation leaves untranslated. Must remain [Language.ENGLISH].
     */
    val FALLBACK_LANGUAGE: Language = Language.ENGLISH

    /** Every language the app ships, in the order the language picker lists them. */
    val SUPPORTED_LANGUAGES: List<Language> = Language.entries

    /**
     * Theme modes offered in the theme picker, in display order. Trim this list to restrict the app
     * to e.g. Light and Dark only.
     */
    val SUPPORTED_THEMES: List<ThemeMode> = ThemeMode.entries
}
