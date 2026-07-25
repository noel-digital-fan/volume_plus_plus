package com.volume_plus_plus.app.data

import android.content.Context

/**
 * Remembers which packages the user has enabled mixing for, so the switches reflect the
 * last applied state instantly without an appops round-trip per app. The actual system
 * state is still owned by appops; this is a fast local mirror kept in sync on every toggle.
 */
class MixPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mix_audio", Context.MODE_PRIVATE)

    fun enabledPackages(): Set<String> =
        prefs.getStringSet(KEY_ENABLED, emptySet())?.toSet() ?: emptySet()

    fun setEnabled(packageName: String, enabled: Boolean) {
        val updated = enabledPackages().toMutableSet().apply {
            if (enabled) add(packageName) else remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ENABLED, updated).apply()
    }

    /** Whether the user has dismissed the "may be unstable" warning banner. */
    fun isWarningDismissed(): Boolean = prefs.getBoolean(KEY_WARNING_DISMISSED, false)

    fun setWarningDismissed(dismissed: Boolean) {
        prefs.edit().putBoolean(KEY_WARNING_DISMISSED, dismissed).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_WARNING_DISMISSED = "warning_dismissed"
    }
}
