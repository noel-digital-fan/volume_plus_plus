package com.volume_plus_plus.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.volume_plus_plus.app.IUserService
import com.volume_plus_plus.app.service.UserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Single entry point for talking to Shizuku. Tracks connection [status] as a flow the UI
 * observes, requests permission, binds the privileged [UserService], and exposes suspend
 * helpers that forward to it off the main thread.
 */
object ShizukuManager {

    enum class Status {
        /** Shizuku app not installed or its service not started. */
        NOT_RUNNING,
        /** Service is up but hasn't granted us access yet. */
        PERMISSION_REQUIRED,
        /** Binding the privileged UserService. */
        CONNECTING,
        /** UserService bound and ready to take calls. */
        READY,
    }

    private const val PERMISSION_CODE = 4919

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var appContext: Context? = null
    private var service: IUserService? = null

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener {
        service = null
        _status.value = Status.NOT_RUNNING
    }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == PERMISSION_CODE) {
            if (grant == PackageManager.PERMISSION_GRANTED) bindService()
            else _status.value = Status.PERMISSION_REQUIRED
        }
    }

    fun init(context: Context) {
        if (appContext != null) {
            refresh()
            return
        }
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refresh()
    }

    fun destroy() {
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermissionResult)
    }

    /** Re-evaluate the current state; safe to call from onResume. */
    fun refresh() {
        if (!Shizuku.pingBinder()) {
            _status.value = Status.NOT_RUNNING
            return
        }
        when {
            hasPermission() -> if (service == null) bindService() else _status.value = Status.READY
            else -> _status.value = Status.PERMISSION_REQUIRED
        }
    }

    /** Kick off the permission dialog (or bind directly if already granted). */
    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _status.value = Status.NOT_RUNNING
            return
        }
        if (hasPermission()) {
            bindService()
            return
        }
        // Pre-11 Shizuku uses a runtime permission model we don't support here.
        if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) {
            _status.value = Status.PERMISSION_REQUIRED
            return
        }
        Shizuku.requestPermission(PERMISSION_CODE)
    }

    private fun hasPermission(): Boolean =
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private val serviceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(appContext!!.packageName, UserService::class.java.name)
        ).daemon(false)
            .processNameSuffix("mixaudio")
            .debuggable(false)
            // Bump whenever UserService's interface changes so Shizuku restarts a fresh service
            // instead of reusing a cached older one (v3 adds isPackageRunning).
            .version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IUserService.Stub.asInterface(binder)
                _status.value = Status.READY
            } else {
                service = null
                _status.value = Status.PERMISSION_REQUIRED
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun bindService() {
        if (service != null) {
            _status.value = Status.READY
            return
        }
        _status.value = Status.CONNECTING
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: Throwable) {
            _status.value = Status.PERMISSION_REQUIRED
        }
    }

    /** Toggle the TAKE_AUDIO_FOCUS app-op. Returns true on success. */
    suspend fun setAudioFocusIgnored(packageName: String, ignore: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext false
            try {
                val result = svc.setAudioFocusMode(packageName, ignore)
                !result.startsWith("ERROR")
            } catch (e: RemoteException) {
                false
            }
        }

    /** Read whether the app currently has audio focus disabled. */
    suspend fun isAudioFocusIgnored(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext false
            try {
                svc.getAudioFocusMode(packageName).contains("ignore")
            } catch (e: RemoteException) {
                false
            }
        }

    /** One active audio player, as reported by the privileged service. */
    data class PlayerSession(val piid: Int, val uid: Int, val packageName: String)

    /** True once the privileged service is bound and ready to take per-app volume calls. */
    val isReady: Boolean get() = service != null

    /** Currently-playing audio players (one per stream), or empty if unavailable. */
    suspend fun getActivePlayers(): List<PlayerSession> =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext emptyList()
            try {
                svc.activePlayers.mapNotNull { entry ->
                    val parts = entry.split('|')
                    if (parts.size != 3) return@mapNotNull null
                    val piid = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val uid = parts[1].toIntOrNull() ?: return@mapNotNull null
                    PlayerSession(piid, uid, parts[2])
                }
            } catch (e: RemoteException) {
                emptyList()
            }
        }

    /** Set a single player's linear volume (0.0..1.0). Returns true on success. */
    suspend fun setPlayerVolume(piid: Int, volume: Float): Boolean =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext false
            try {
                svc.setPlayerVolume(piid, volume)
            } catch (e: RemoteException) {
                false
            }
        }

    /**
     * Whether any process for [packageName] is currently alive — used to tell a still-open app
     * (screen off, backgrounded, paused) from one the user closed or force-stopped. Defaults to
     * true when the privileged service is unavailable or errors, so a live per-app volume session
     * is never discarded just because the link is momentarily down.
     */
    suspend fun isPackageRunning(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext true
            try {
                svc.isPackageRunning(packageName)
            } catch (e: RemoteException) {
                true
            }
        }
}
