package com.volume_plus_plus.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostPolicyTest {
    @Test fun defaultsAndCorruptLimitsStayWithinTheSupportedRange() {
        assertEquals(4, BoostPolicy.DEFAULT_MAX_STEPS)
        assertEquals(4, BoostPolicy.normalizeMaxSteps(Int.MIN_VALUE))
        assertEquals(10, BoostPolicy.normalizeMaxSteps(Int.MAX_VALUE))
        for (steps in 4..10) assertEquals(steps, BoostPolicy.normalizeMaxSteps(steps))
    }

    @Test fun allSevenCeilingsPreserveTheExistingDisplayAndGainScale() {
        val percentages = listOf(120, 125, 130, 135, 140, 145, 150)
        val gains = listOf(600, 750, 900, 1050, 1200, 1350, 1500)
        for ((index, steps) in (4..10).withIndex()) {
            assertEquals(percentages[index], BoostPolicy.percent(steps))
            assertEquals(gains[index], BoostPolicy.gain(steps))
        }
        assertEquals(100, BoostPolicy.percent(0))
        assertEquals(0, BoostPolicy.gain(0))
    }

    @Test fun untrustedGainRequestsHaveAnAbsoluteServiceLimit() {
        assertEquals(0, BoostPolicy.clampGain(Int.MIN_VALUE))
        assertEquals(600, BoostPolicy.clampGain(600))
        assertEquals(1500, BoostPolicy.clampGain(Int.MAX_VALUE))
        assertEquals(1500, BoostPolicy.gain(Int.MAX_VALUE))
    }
}
