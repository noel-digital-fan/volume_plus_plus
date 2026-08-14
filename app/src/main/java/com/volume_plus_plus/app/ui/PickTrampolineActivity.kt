package com.volume_plus_plus.app.ui

import android.app.Activity
import android.os.Bundle
import com.volume_plus_plus.app.service.ColorPickService

/**
 * Closes the notification shade, then asks [ColorPickService] for a frame.
 *
 * The eyedropper's "Pick colour" notification action can't simply start the service: a service or
 * broadcast `PendingIntent` leaves the shade open, so the screenshot the user then picks from would
 * be of the shade rather than of the app underneath it. Starting an *activity* is what collapses it,
 * so this one exists purely to be started — it draws nothing (translucent theme, no content view)
 * and finishes before it can be seen.
 */
class PickTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ColorPickService.send(this, ColorPickService.ACTION_PICK)
        finish()
        // No slide-in/out: this activity is machinery, and animating it would be a visible flicker.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
