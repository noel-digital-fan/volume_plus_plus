package com.volume_plus_plus.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.volume_plus_plus.app.i18n.Localization
import com.volume_plus_plus.app.ui.PanelTrampolineActivity

/**
 * A Quick Settings tile that opens the Volume++ panel.
 *
 * This is the way in that doesn't go through the hardware keys: it works on a phone whose volume
 * buttons have worn out or broken, and it needs only draw-over-other-apps to do it — the
 * accessibility service exists to capture the *keys*, and there are no keys involved here. When that
 * service happens to be running the tile drives its panel rather than a second one of its own; see
 * [com.volume_plus_plus.app.overlay.PanelLauncher].
 */
class VolumePanelTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        // The manifest's android:label is what the OS prints in the tile picker, and — like the
        // accessibility service's label — it follows the *device* language, because the system draws
        // it out of our APK before we run. Once the tile is placed we do get to name it, so from here
        // on it tracks the app's own language setting instead.
        tile.label = Localization.strings.qsTileLabel
        // Not a toggle: it opens something. Inactive is the resting look for that.
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    // The Intent overload of startActivityAndCollapse is deprecated, but it is also the only one
    // that exists below Android 14 — the PendingIntent replacement was added in 14, and the Intent
    // form throws there — so both are needed and the branch below picks the right one per release.
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        // NO_USER_ACTION: starting the trampoline would otherwise count as the user leaving whatever
        // app is in front, and video apps answer that (onUserLeaveHint / auto-enter PiP) by shrinking
        // the video into a mini player — the panel is meant to open over the app, not send it away.
        val intent = Intent(this, PanelTrampolineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 dropped the Intent overload — it throws now — in favour of a PendingIntent.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
