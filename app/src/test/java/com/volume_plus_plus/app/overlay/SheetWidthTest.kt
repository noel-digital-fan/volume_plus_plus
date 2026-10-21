package com.volume_plus_plus.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SheetWidthTest {

    @Test
    fun portraitStartsAtFullWidthOnEveryStyle() {
        OverlayVersion.entries.forEach {
            assertEquals(it.name, 1f, it.defaultSheetWidth(EditOrientation.PORTRAIT))
        }
    }

    @Test
    fun landscapeDefaultsFollowTheStyle() {
        assertEquals(0.8f, OverlayVersion.ANDROID_16.defaultSheetWidth(EditOrientation.LANDSCAPE))
        assertEquals(0.8f, OverlayVersion.ANDROID_15.defaultSheetWidth(EditOrientation.LANDSCAPE))
        listOf(
            OverlayVersion.ANDROID_14, OverlayVersion.ANDROID_13, OverlayVersion.ANDROID_12,
            OverlayVersion.ANDROID_11, OverlayVersion.ANDROID_10, OverlayVersion.ANDROID_9,
        ).forEach { assertEquals(it.name, 0.75f, it.defaultSheetWidth(EditOrientation.LANDSCAPE)) }
        assertEquals(1f, OverlayVersion.ANDROID_8.defaultSheetWidth(EditOrientation.LANDSCAPE))
        assertEquals(1f, OverlayVersion.ANDROID_7.defaultSheetWidth(EditOrientation.LANDSCAPE))
    }

    @Test
    fun widthsAreSetPerOrientationAndResetToTheDefault() {
        val set = ComponentCustomization().withSheetWidth(EditOrientation.LANDSCAPE, 0.6f)
        assertEquals(0.6f, set.sheetWidth(EditOrientation.LANDSCAPE))
        assertNull(set.sheetWidth(EditOrientation.PORTRAIT))
        // Out-of-range values are clamped to what the sheet allows.
        assertEquals(MIN_SHEET_WIDTH, set.withSheetWidth(EditOrientation.PORTRAIT, 0.1f).sheetWidth(EditOrientation.PORTRAIT))
        assertNull(set.withDefaultWidths().sheetWidth(EditOrientation.LANDSCAPE))
    }
}
