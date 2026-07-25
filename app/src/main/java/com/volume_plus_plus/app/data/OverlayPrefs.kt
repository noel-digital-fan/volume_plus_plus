package com.volume_plus_plus.app.data

import android.content.Context
import com.volume_plus_plus.app.overlay.IconSet
import com.volume_plus_plus.app.overlay.LegacyVersion
import com.volume_plus_plus.app.overlay.OverlaySkin
import com.volume_plus_plus.app.overlay.SevenEightVersion
import com.volume_plus_plus.app.overlay.defaultSkin

/**
 * Remembers the user's chosen overlay skin. Defaults to the best match for the device's Android
 * version ([defaultSkin]) until the user picks one. Read by both the settings UI and the overlay
 * so a change applies the next time the panel shows.
 */
class OverlayPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun getSkin(): OverlaySkin {
        val name = prefs.getString(KEY_SKIN, null) ?: return defaultSkin()
        return runCatching { OverlaySkin.valueOf(name) }.getOrDefault(defaultSkin())
    }

    fun setSkin(skin: OverlaySkin) {
        prefs.edit().putString(KEY_SKIN, skin.name).apply()
    }

    /** The icon set for the Android 13–15 skin (which OS revision's panel to mimic). Defaults to
     *  Android 15 so a first-run install opens on the newest style (see [defaultSkin]). */
    fun getIconSet(): IconSet {
        val name = prefs.getString(KEY_ICON_SET, null) ?: return IconSet.ANDROID_15
        return runCatching { IconSet.valueOf(name) }.getOrDefault(IconSet.ANDROID_15)
    }

    fun setIconSet(set: IconSet) {
        prefs.edit().putString(KEY_ICON_SET, set.name).apply()
    }

    /** The sub-version for the Android 9–11 skin (which of 9 / 10 / 11 to mimic). */
    fun getLegacyVersion(): LegacyVersion {
        val name = prefs.getString(KEY_LEGACY_VERSION, null) ?: return LegacyVersion.ANDROID_11
        return runCatching { LegacyVersion.valueOf(name) }.getOrDefault(LegacyVersion.ANDROID_11)
    }

    fun setLegacyVersion(version: LegacyVersion) {
        prefs.edit().putString(KEY_LEGACY_VERSION, version.name).apply()
    }

    /** The sub-version for the Android 7–8 skin (which of 7 / 8 to mimic). */
    fun getSevenEightVersion(): SevenEightVersion {
        val name = prefs.getString(KEY_SEVEN_EIGHT_VERSION, null) ?: return SevenEightVersion.ANDROID_8
        return runCatching { SevenEightVersion.valueOf(name) }.getOrDefault(SevenEightVersion.ANDROID_8)
    }

    fun setSevenEightVersion(version: SevenEightVersion) {
        prefs.edit().putString(KEY_SEVEN_EIGHT_VERSION, version.name).apply()
    }

    private companion object {
        const val KEY_SKIN = "skin"
        const val KEY_ICON_SET = "icon_set"
        const val KEY_LEGACY_VERSION = "legacy_version"
        const val KEY_SEVEN_EIGHT_VERSION = "seven_eight_version"
    }
}
