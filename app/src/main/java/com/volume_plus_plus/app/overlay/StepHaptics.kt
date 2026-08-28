package com.volume_plus_plus.app.overlay

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The volume-step tick: one short buzz per volume step while a volume key is held.
 *
 * Lives here rather than in the service so the Overlay tab can play the *same* tick while the user
 * drags the intensity slider — what they feel while setting it up is exactly what a held key gives
 * them later, and a silent slider immediately tells them the problem is the vibrator rather than the
 * key handling.
 *
 * The tick has to clear the hardware's own floor to be felt at all: a motor needs roughly 20 ms to
 * spin up, which is why the system's own touch feedback runs 30–75 ms. An earlier build asked for
 * 8 ms at 21% amplitude — dutifully played by the platform, and felt by nobody.
 */
object StepHaptics {

    /** Shortest tick a vibrator can actually start and be felt through (ms). */
    private const val BASE_MS = 14f

    /** Ceiling for the duration-carries-intensity paths: under the fastest repeat gap, so two ticks
     *  stay separate pulses instead of smearing into one buzz. */
    private const val MAX_MS = 35L

    /** The device's main vibrator. */
    fun vibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /**
     * Play one tick at [intensity] (the Haptic intensity setting, 0.5–2.0; 1 = default).
     *
     * Which path depends on what the vibrator can actually do, since most phones cannot vary how hard
     * they buzz:
     * - **Amplitude control** (API 26+, hardware supports it): a short one-shot whose amplitude the
     *   intensity drives directly — the smoothest of the three, a true 50–200% range.
     * - **No amplitude control, API 29+**: the platform's own tuned tick/click effects, the ones the
     *   launcher and keyboard use on that device. Amplitude would be ignored, so intensity picks
     *   between the three effects by weight instead.
     * - **Anything older**: a plain one-shot where intensity carries into the duration, the only knob
     *   left.
     *
     * The vibration is tagged as touch feedback (API 33+) / sonification (API 26+) rather than left
     * unspecified, so Do Not Disturb doesn't swallow it.
     */
    fun play(vibrator: Vibrator, intensity: Float) {
        runCatching {
            if (!vibrator.hasVibrator()) return
            val amplitudeControl =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when {
                    amplitudeControl -> VibrationEffect.createOneShot(
                        (BASE_MS + 6f * intensity).roundToLong(),
                        (55f + 75f * intensity).roundToInt().coerceIn(1, 255),
                    )
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(
                        when {
                            intensity < 0.85f -> VibrationEffect.EFFECT_TICK
                            intensity <= 1.4f -> VibrationEffect.EFFECT_CLICK
                            else -> VibrationEffect.EFFECT_HEAVY_CLICK
                        },
                    )
                    else -> VibrationEffect.createOneShot(
                        durationFor(intensity),
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(
                        effect,
                        VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_TOUCH)
                            .build(),
                    )
                } else {
                    // Superseded by VibrationAttributes above; still the only way to say it on 26–32.
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, sonificationAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationFor(intensity), sonificationAttrs)
            }
        }
    }

    /** Tick length (ms) where duration is the only thing that can carry the intensity setting. */
    private fun durationFor(intensity: Float): Long =
        (BASE_MS + 16f * intensity).roundToLong().coerceAtMost(MAX_MS)

    /** "This is feedback, not a notification" for API 26–32, which has no [VibrationAttributes]. */
    private val sonificationAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
