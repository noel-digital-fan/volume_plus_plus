package com.volume_plus_plus.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volume_plus_plus.app.config.AppSettings

/**
 * How the rest of the app reaches the current translation.
 *
 * Two entry points, because half this app isn't Compose — the volume panel is a WindowManager
 * overlay built out of plain Views, and the eyedropper posts a notification from a service:
 *
 * - **Compose**: `val s = strings()`, backed by [LocalStrings]. Changing language recomposes.
 * - **Everywhere else**: [Localization.strings], a plain read of whatever is current now.
 *
 * The language itself is owned by [AppSettings]; this only maps it to a [Strings].
 */
object Localization {

    /**
     * The translation in effect. Falls back to [EnglishStrings] before [AppSettings.init] has run,
     * which in practice it always has — `VolumeApp.onCreate` precedes every other component.
     */
    val strings: Strings get() = AppSettings.language.value.strings
}

/**
 * The translation for the current composition. Static, so a language change invalidates the whole
 * subtree rather than tracking every string read individually — that is what we want here, since
 * changing language re-renders essentially the entire UI anyway.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { EnglishStrings }

/** The current translation. Shorthand for `LocalStrings.current`. */
@Composable
@ReadOnlyComposable
fun strings(): Strings = LocalStrings.current

/**
 * The language currently in effect, observed. Use at the composition root to provide [LocalStrings];
 * everything below reads [strings] instead.
 */
@Composable
fun rememberStrings(): Strings {
    val language by AppSettings.language.collectAsStateWithLifecycle()
    return remember(language) { language.strings }
}
