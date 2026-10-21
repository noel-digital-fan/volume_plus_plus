package com.volume_plus_plus.app.audio

/** The app's display scale: each five percentage points requests another 1.5 dB. */
object BoostPolicy {
    const val DEFAULT_MAX_STEPS = 4
    const val MIN_MAX_STEPS = 4
    const val HARD_MAX_STEPS = 10
    const val PERCENT_PER_STEP = 5
    const val GAIN_PER_STEP_MB = 150
    const val HARD_MAX_GAIN_MB = HARD_MAX_STEPS * GAIN_PER_STEP_MB

    fun normalizeMaxSteps(value: Int): Int = value.coerceIn(MIN_MAX_STEPS, HARD_MAX_STEPS)
    fun percent(steps: Int): Int = 100 + steps.coerceIn(0, HARD_MAX_STEPS) * PERCENT_PER_STEP
    fun gain(steps: Int): Int = steps.coerceIn(0, HARD_MAX_STEPS) * GAIN_PER_STEP_MB
    fun clampGain(millibels: Int): Int = millibels.coerceIn(0, HARD_MAX_GAIN_MB)
}
