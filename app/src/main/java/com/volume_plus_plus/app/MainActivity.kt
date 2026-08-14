package com.volume_plus_plus.app

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.volume_plus_plus.app.config.AppSettings
import com.volume_plus_plus.app.data.MixPrefs
import com.volume_plus_plus.app.i18n.LocalStrings
import com.volume_plus_plus.app.i18n.rememberStrings
import com.volume_plus_plus.app.overlay.ScreenColorPicker
import com.volume_plus_plus.app.privileged.PrivilegedManager
import com.volume_plus_plus.app.ui.MainScreen
import com.volume_plus_plus.app.ui.theme.ThemeMode
import com.volume_plus_plus.app.ui.theme.VolumeTheme

class MainActivity : ComponentActivity(), ScreenColorPicker.Host {

    private val prefs by lazy { MixPrefs(this) }

    /**
     * Screen-capture consent for the colour editor's eyedropper. Registered as a field so it is in
     * place before the activity reaches STARTED — `registerForActivityResult` throws if it is called
     * any later. The picker itself lives in a WindowManager overlay with no activity of its own, so
     * this is how it reaches the system dialog.
     */
    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> ScreenColorPicker.onConsentResult(result.resultCode, result.data) }

    /**
     * The eyedropper's foreground service posts the notification the user drives the pick from, so
     * on Android 13+ it asks for notification access first. A refusal doesn't block the feature —
     * the picker also puts a floating control on screen — so the result is simply ignored and the
     * consent dialog follows either way.
     */
    private val notificationConsent = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { launchCaptureConsent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PrivilegedManager.init(this)
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

    override fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationConsent.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchCaptureConsent()
    }

    private fun launchCaptureConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val launched = runCatching {
            captureConsent.launch(manager.createScreenCaptureIntent())
        }.isSuccess
        if (!launched) ScreenColorPicker.cancel()
    }

    override fun onResume() {
        super.onResume()
        // The user may have started Shizuku or granted access while we were backgrounded.
        PrivilegedManager.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            PrivilegedManager.destroy()
        }
    }
}
