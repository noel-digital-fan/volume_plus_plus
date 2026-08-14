package com.volume_plus_plus.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volume_plus_plus.app.config.AppSettings
import com.volume_plus_plus.app.data.MixPrefs
import com.volume_plus_plus.app.i18n.LocalStrings
import com.volume_plus_plus.app.i18n.rememberStrings
import com.volume_plus_plus.app.shizuku.ShizukuManager
import com.volume_plus_plus.app.ui.MainScreen
import com.volume_plus_plus.app.ui.theme.ThemeMode
import com.volume_plus_plus.app.ui.theme.VolumeTheme

class MainActivity : ComponentActivity() {

    private val prefs by lazy { MixPrefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuManager.init(this)
        setContent {
            // Both settings live in AppSettings rather than in composition state: the overlay and the
            // accessibility service read the same values, and they have no route back into the UI.
            val themeMode by AppSettings.themeMode.collectAsStateWithLifecycle()
            val language by AppSettings.languageOverride.collectAsStateWithLifecycle()
            val strings = rememberStrings()

            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Re-apply edge-to-edge every time the resolved theme flips so the status- and
            // navigation-bar icon contrast tracks the in-app choice immediately. Calling it once in
            // onCreate would leave the bars stuck on whatever the device theme was at launch, which
            // is what made Light/Dark/System look like they "didn't apply".
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                )
                onDispose {}
            }

            CompositionLocalProvider(LocalStrings provides strings) {
                VolumeTheme(themeMode = themeMode) {
                    MainScreen(
                        prefs = prefs,
                        themeMode = themeMode,
                        onThemeModeChange = AppSettings::setThemeMode,
                        language = language,
                        onLanguageChange = AppSettings::setLanguage,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku or granted access while we were backgrounded.
        ShizukuManager.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ShizukuManager.destroy()
        }
    }
}
