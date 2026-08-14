package com.volume_plus_plus.app.i18n

import java.util.Locale

/**
 * A language the app ships a translation for.
 *
 * ### Adding a language (two edits)
 * 1. Create `StringsXx.kt` next to this file: an `object` extending [Strings] that overrides the
 *    keys you have translated. Anything you leave out keeps its English text, so a partial
 *    translation is perfectly fine to submit — see `docs/TRANSLATING.md`.
 * 2. Add an entry below with the language's ISO 639-1 code, its **endonym** (the language's name in
 *    itself — `Deutsch`, not `German`, since that entry has to be readable to someone who only
 *    speaks it), and your new object.
 *
 * That is the whole registration: the picker, the system-locale detection in [forLocale] and the
 * fallback chain all read this enum, so nothing else needs touching.
 *
 * @param code ISO 639-1 code, matched against the device locale by [forLocale].
 * @param endonym The language's name in its own language, as shown in the picker. Never translated.
 * @param strings The translation itself.
 */
enum class Language(
    val code: String,
    val endonym: String,
    val strings: Strings,
) {
    ENGLISH("en", "English", EnglishStrings),
    SPANISH("es", "Español", SpanishStrings),
    PORTUGUESE("pt", "Português", PortugueseStrings),
    FRENCH("fr", "Français", FrenchStrings),
    ;

    companion object {

        /**
         * The language to use for [locale], which is normally the device's current locale.
         *
         * Matching is on the language subtag alone, so every regional variant lands on the same
         * translation — `pt-BR` and `pt-PT` both get [PORTUGUESE], `es-MX` and `es-ES` both get
         * [SPANISH]. Anything we don't ship falls back to [ENGLISH].
         */
        fun forLocale(locale: Locale): Language {
            // Locale.language normalises the code but also carries legacy aliases ("iw" for Hebrew
            // and friends), so compare on the normalised value rather than the raw tag.
            val code = locale.language.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.code == code } ?: ENGLISH
        }

        /** The [Language] whose [code] is [code], or null if we don't ship it. */
        fun forCode(code: String?): Language? {
            if (code == null) return null
            val normalised = code.lowercase(Locale.ROOT)
            return entries.firstOrNull { it.code == normalised }
        }
    }
}
