package com.volume_plus_plus.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.volume_plus_plus.app.overlay.AppVolumeController
import com.volume_plus_plus.app.overlay.OverlayController
import com.volume_plus_plus.app.overlay.StepHaptics
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.privileged.PrivilegedManager

/**
 * Captures the hardware volume keys and, instead of the stock system panel, raises our own
 * [OverlayController] panel with per-app sliders. Requires the user to enable this accessibility
 * service; key filtering is declared in res/xml/volume_key_service.xml.
 *
 * If overlay permission (SYSTEM_ALERT_WINDOW) isn't granted we don't consume the keys, so the
 * normal system volume UI keeps working until the user finishes setup.
 */
class VolumeKeyService : AccessibilityService() {

    private var overlay: OverlayController? = null
    /** Remembers and re-applies per-app volumes across playback sessions, independent of the panel. */
    private var appVolume: AppVolumeController? = null
    private val prefs by lazy { OverlayPrefs(this) }
    private val vibrator: Vibrator by lazy { StepHaptics.vibrator(this) }

    private val handler = Handler(Looper.getMainLooper())
    /** Direction of the currently-held volume key (+1 up, -1 down, 0 = none). */
    private var heldDirection = 0
    /** Current gap between repeats; shrinks as the key is held so the ramp speeds up. */
    private var repeatDelay = REPEAT_START_MS
    /** Keeps nudging the volume while a key stays down, accelerating like the stock panel. */
    private val repeat = object : Runnable {
        override fun run() {
            if (heldDirection == 0) return
            if (overlay?.adjustAndShow(heldDirection) == true && prefs.isHoldStepHapticsEnabled()) {
                tick()
            }
            repeatDelay = (repeatDelay - REPEAT_STEP_MS).coerceAtLeast(REPEAT_MIN_MS)
            handler.postDelayed(this, repeatDelay)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Ensure the privileged link is alive even if the main UI was never opened this process.
        PrivilegedManager.init(this)
        // Guard against a repeat connect leaking the previous instances (a live playback callback and
        // poll loop): tear them down before rebuilding.
        overlay?.destroy()
        appVolume?.destroy()
        val volume = AppVolumeController(this).also { it.start() }
        appVolume = volume
        overlay = OverlayController(this, volume)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeKey) return super.onKeyEvent(event)

        // The user asked for Android's built-in volume control, so don't consume the keys — the
        // system panel handles them exactly as it would without this app. Read live (the setting can
        // be flipped while the service stays connected); a held repeat is dropped below with the key.
        if (prefs.isSystemVolumePanelEnabled()) {
            heldDirection = 0
            handler.removeCallbacks(repeat)
            return super.onKeyEvent(event)
        }

        // Without draw-over-other-apps we can't show our panel — leave the system UI in charge.
        if (!Settings.canDrawOverlays(this)) return super.onKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Drive the repeat ourselves off the first press; ignore the system's auto-repeat
                // downs (repeatCount > 0) so the two don't both fire and double the step.
                if (event.repeatCount == 0) {
                    heldDirection = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                    repeatDelay = REPEAT_START_MS
                    overlay?.adjustAndShow(heldDirection)
                    handler.removeCallbacks(repeat)
                    handler.postDelayed(repeat, FIRST_REPEAT_MS)
                }
            }

            KeyEvent.ACTION_UP -> {
                heldDirection = 0
                handler.removeCallbacks(repeat)
            }
        }
        // Consume both down and up so the system panel never appears.
        return true
    }

    /**
     * Track which app is in the foreground so [OverlayController] can surface a currently-active app
     * that's playing audio at the top of its per-app sliders. Window-state-changed events carry the
     * package without needing to inspect window content, and this works uniformly on Android 7–15.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrEmpty() && pkg != packageName) {
            overlay?.foregroundPackage = pkg
        }
    }

    override fun onInterrupt() { /* not used */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        heldDirection = 0
        handler.removeCallbacks(repeat)
        overlay?.destroy()
        overlay = null
        appVolume?.destroy()
        appVolume = null
        return super.onUnbind(intent)
    }

    /** One step tick. Shared with the Overlay tab's intensity slider, so what the user feels while
     *  setting the level up is exactly what a held key gives them. */
    private fun tick() = StepHaptics.play(vibrator, prefs.getHoldStepHapticIntensity())

    private companion object {
        /** Delay before a held key starts repeating (ms). Short so a hold responds almost at once. */
        const val FIRST_REPEAT_MS = 250L
        /** Repeat pacing: starts at [REPEAT_START_MS] and shrinks by [REPEAT_STEP_MS] each step down
         *  to [REPEAT_MIN_MS], so a long hold accelerates. Small steps keep the ramp smooth. */
        const val REPEAT_START_MS = 150L
        const val REPEAT_STEP_MS = 12L
        const val REPEAT_MIN_MS = 40L
    }
}
