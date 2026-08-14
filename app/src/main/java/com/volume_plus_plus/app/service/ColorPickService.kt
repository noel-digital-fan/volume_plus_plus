package com.volume_plus_plus.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import com.volume_plus_plus.app.R
import com.volume_plus_plus.app.i18n.Localization
import com.volume_plus_plus.app.overlay.ScreenColorPicker
import com.volume_plus_plus.app.ui.PickTrampolineActivity

/** The current translation. A getter, so these Views pick up a language change on their next build. */
private val strings get() = Localization.strings


/**
 * Holds the screen-capture session open while the user goes and finds the colour they want.
 *
 * MediaProjection is only allowed from a foreground service of the `mediaProjection` type, so this
 * service exists to satisfy that rule — and the notification it has to post doubles as the user's
 * control surface: **Pick colour** grabs a frame of whatever is on screen, **Cancel** abandons the
 * whole thing. [ScreenColorPicker] puts a floating pill on screen carrying the same two actions,
 * because a notification alone can't be relied on (see [notification]).
 *
 * Only a single frame is ever captured, at the moment the user asks for one. Freezing that frame and
 * picking from it — rather than sampling a live stream — is what lets the magnifier exist at all:
 * the loupe overlay isn't on screen yet when the frame is taken, so it can never end up magnifying
 * itself.
 */
class ColorPickService : Service() {

    private var projection: MediaProjection? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var capturing = false

    /** Held as a field rather than only in [grabFrame]'s local, so teardown can always release it —
     *  a frame can in principle arrive before `createVirtualDisplay` has finished returning. */
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PICK -> capture()
            ACTION_CANCEL -> finish()
            else -> finish()
        }
        return START_NOT_STICKY
    }

    /**
     * Go foreground *first*, then take the projection. Android 14 rejects `getMediaProjection` from
     * a service that isn't already foreground with the matching type, and the order is legal on
     * every other version too, so it isn't branched.
     */
    private fun start(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null) {
            finish()
            return
        }
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            finish()
            return
        }
        // Mandatory before createVirtualDisplay on Android 14+, and the only way to hear about the
        // user revoking capture from the status-bar chip (or the screen locking) on any version.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { finish() }
            }
        }, mainHandler)
        this.projection = projection

        captureThread = HandlerThread("colour-pick-capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        ScreenColorPicker.onCaptureReady()
    }

    /**
     * Grab one frame and hand it to [ScreenColorPicker].
     *
     * The picker's own floating pill is taken off screen first and the grab is posted behind a short
     * settle delay, so neither the pill nor a half-finished shade animation ends up baked into the
     * frame the user then picks from.
     */
    private fun capture() {
        if (capturing || projection == null) return
        capturing = true
        ScreenColorPicker.hideChromeForCapture()
        mainHandler.postDelayed({ grabFrame() }, SETTLE_MS)
    }

    private fun grabFrame() {
        val projection = this.projection
        val handler = captureHandler
        if (projection == null || handler == null) {
            finish()
            return
        }
        val size = displaySize()
        val width = size.first
        val height = size.second
        if (width <= 0 || height <= 0) {
            finish()
            return
        }

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var delivered = false

        val deliver = deliver@{ bitmap: Bitmap? ->
            if (delivered) return@deliver
            delivered = true
            releaseDisplay()
            runCatching { reader.close() }
            mainHandler.post {
                if (bitmap == null) {
                    ScreenColorPicker.onCaptureFailed()
                    finish(notifyPicker = false)
                } else {
                    // One frame is all this projection can give — Android 14+ allows a single
                    // virtual display per session — and the picking itself happens entirely in an
                    // overlay, so the service has nothing left to do. Shutting down here is what
                    // takes the notification and the screen-capture indicator away the instant the
                    // loupe appears, rather than leaving them up behind it.
                    ScreenColorPicker.onFrameCaptured(bitmap)
                    finish(notifyPicker = false)
                }
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            val bitmap = runCatching { image.toBitmap(width, height) }.getOrNull()
            runCatching { image.close() }
            if (bitmap != null) deliver(bitmap)
        }, handler)

        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                "vpp-eyedropper",
                width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler,
            )
        }.getOrNull()
        if (virtualDisplay == null) {
            deliver(null)
            return
        }
        // A projection that never produces a frame answers with silence, so give up on our own.
        mainHandler.postDelayed({ deliver(null) }, CAPTURE_TIMEOUT_MS)
    }

    /**
     * Copy an [Image] out as an ARGB bitmap.
     *
     * The plane's rows are padded out to a hardware alignment, so its stride is generally *wider*
     * than the display. Copying it as if it weren't is what produces the classic diagonally-skewed
     * screenshot; the padding columns have to be allocated and then cropped off.
     */
    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    /** The full display, system bars and cutout included, so its coordinates match the overlay's. */
    private fun displaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                strings.eyedropperChannelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = strings.eyedropperChannelDescription
                setShowBadge(false)
            },
        )
    }

    /**
     * The ongoing notification the user drives the pick from.
     *
     * **Pick colour** goes through [PickTrampolineActivity] rather than straight to this service on
     * purpose: only starting an activity collapses the notification shade, and a service or
     * broadcast action would leave it open — so the "screen" that got captured would be the shade.
     *
     * `setOngoing` genuinely blocks swipe-away up to Android 13. From 14 the user can dismiss it
     * anyway, which is why [ScreenColorPicker] also puts a floating pill on screen: re-posting a
     * notification somebody just dismissed would be obnoxious, so the guarantee is delivered with a
     * control the app can honestly keep in front of them instead.
     */
    private fun notification(): Notification {
        val pick = PendingIntent.getActivity(
            this, 1,
            Intent(this, PickTrampolineActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 2,
            Intent(this, ColorPickService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_search)
            .setContentTitle(strings.eyedropperNotificationTitle)
            .setContentText(strings.eyedropperNotificationText)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, strings.eyedropperPick, pick).build())
            .addAction(Notification.Action.Builder(null, strings.cancel, cancel).build())
            .build()
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
    }

    private fun releaseProjection() {
        releaseDisplay()
        runCatching { projection?.stop() }
        projection = null
    }

    /**
     * Tear everything down. [notifyPicker] tells [ScreenColorPicker] the flow is over — true when
     * the service is stopping for a reason of its own (the Cancel action, revoked capture, the
     * screen locking), false when the picker already knows because it's the one still working.
     */
    private fun finish(notifyPicker: Boolean = true) {
        releaseProjection()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        capturing = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        if (notifyPicker) ScreenColorPicker.onServiceStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProjection()
        captureThread?.quitSafely()
        captureThread = null
    }

    companion object {
        private const val CHANNEL_ID = "colour_picker"
        private const val NOTIFICATION_ID = 4920

        /** How long to let the screen settle after the pill is pulled and the shade collapses. */
        private const val SETTLE_MS = 350L
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        const val ACTION_START = "com.volume_plus_plus.app.PICK_START"
        const val ACTION_PICK = "com.volume_plus_plus.app.PICK_FRAME"
        const val ACTION_CANCEL = "com.volume_plus_plus.app.PICK_CANCEL"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ColorPickService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun send(context: Context, action: String) {
            runCatching {
                context.startService(Intent(context, ColorPickService::class.java).setAction(action))
            }
        }
    }
}
