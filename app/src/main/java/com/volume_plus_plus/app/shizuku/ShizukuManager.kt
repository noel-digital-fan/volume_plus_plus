package com.volume_plus_plus.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import com.volume_plus_plus.app.IUserService
import com.volume_plus_plus.app.service.UserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Single entry point for talking to Shizuku. Tracks [setup] as a flow the UI observes, requests
 * permission, binds the privileged [UserService], and exposes suspend helpers that forward to it
 * off the main thread.
 */
object ShizukuManager {

    /**
     * Everything the mixing page needs to know, as independent observations rather than one
     * exclusive state. Each of [installed], [serviceRunning] and [accessGranted] is measured on its
     * own and reported on its own, so a check that reads wrong shows up as the wrong tick on the
     * checklist instead of quietly impersonating a different step.
     */
    data class Setup(
        /** The Shizuku app is present on the device. */
        val installed: Boolean = false,
        /**
         * A Shizuku service is running *and can still authorise apps* — the app's service, or Sui on
         * rooted devices. Deliberately stricter than "a binder answers": a service left behind by an
         * older install of the Shizuku app keeps answering long after the app that governed it is
         * gone, and nothing sent to it can ever be approved. See [serverUnusable].
         */
        val serviceRunning: Boolean = false,
        /** That service has authorised Volume++. */
        val accessGranted: Boolean = false,
        /** Binding the privileged UserService right now. */
        val connecting: Boolean = false,
        /** Authorised, but the privileged UserService never came back. */
        val connectFailed: Boolean = false,
        /** The privileged UserService is bound and taking calls. */
        val ready: Boolean = false,
        /**
         * A Shizuku service is answering, but an authorisation request to it went unanswered, so it
         * is orphaned: still alive from a previous install of the Shizuku app, which is exactly why
         * the Shizuku app itself reports it as not running. Only the user can clear this, by
         * stopping that leftover service and starting Shizuku again.
         */
        val serverUnusable: Boolean = false,
    )

    /** Package name of the Shizuku app, used to check install state and to launch it. */
    const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    private const val PERMISSION_CODE = 4919

    /**
     * How long to wait for [Shizuku.bindUserService] to call back before treating the attempt as
     * failed. Shizuku has to spawn a whole `app_process` VM, which is slow on weak devices, so this
     * is generous — it only has to be shorter than the user's patience.
     */
    private const val BIND_TIMEOUT_MS = 15_000L

    /**
     * How long to wait for an authorisation request before concluding the service can't grant one.
     * A healthy Shizuku hands the request to its app, which puts a dialog in front of the user; an
     * orphaned one has no app left to hand it to and simply says nothing, and that silence is the
     * only evidence a client ever gets. Long enough that a slow device — or a user who takes a
     * moment to read the dialog before answering — is never mistaken for silence.
     */
    private const val PERMISSION_TIMEOUT_MS = 20_000L

    private val _setup = MutableStateFlow(Setup())
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    private var appContext: Context? = null
    private var service: IUserService? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set while a [Shizuku.bindUserService] call is outstanding, so refreshes don't stack binds. */
    private var binding = false
    private var timeoutJob: Job? = null

    /** Latched when a bind attempt gives up, so the checklist can offer a retry instead of spinning. */
    private var connectFailed = false

    /** Whether this process has already cleared any user service left over by a previous one. */
    private var clearedStaleService = false

    /** Latched when an authorisation request goes unanswered. See [Setup.serverUnusable]. */
    private var serverUnusable = false
    private var permissionTimeoutJob: Job? = null

    /**
     * Shizuku's listeners are process-wide and hold nothing but this singleton, so they are
     * registered once and kept for the life of the process. [init] can be called by more than one
     * component, and in any order.
     */
    private var listenersRegistered = false

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh(force = true) }
    private val onBinderDead = Shizuku.OnBinderDeadListener { refresh(force = false) }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code != PERMISSION_CODE) return@OnRequestPermissionResultListener
        // An answer of any kind — allow or deny — proves the service still reaches its app, which is
        // the whole question [serverUnusable] exists to settle.
        cancelPermissionTimeout()
        serverUnusable = false
        refresh(force = true)
    }

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (!listenersRegistered) {
            listenersRegistered = true
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermissionResult)
        }
        refresh()
    }

    /**
     * Drop work in flight. The Shizuku listeners are deliberately left registered: they belong to
     * this process-wide singleton rather than to whoever called [init], and the accessibility
     * service keeps using the privileged link long after the UI has gone. Removing them here used
     * to leave the singleton permanently deaf, because [init] would then find [appContext] already
     * set and never register them again.
     */
    fun destroy() {
        cancelTimeout()
        cancelPermissionTimeout()
    }

    /**
     * Re-measure everything and connect if the way is clear. Cheap enough to call on a timer, which
     * the mixing page does — the user leaves to install Shizuku, to start it, or to authorise us,
     * and every one of those has to tick its own step without them having to come back and forth.
     */
    fun refresh() = refresh(force = false)

    /** Start over after a failed connection, discarding whatever Shizuku still has cached. */
    fun retry() = refresh(force = true)

    /**
     * [force] means "the situation changed, try binding again even if we already gave up": Shizuku
     * just handed us a binder, access was granted, or the user asked to retry. Without it a failed
     * bind stays failed, so a plain refresh can't put the user back on an endless spinner.
     */
    private fun refresh(force: Boolean) {
        if (!serverAnswering()) {
            // Shizuku's service is gone, and so is anything it had spawned for us. A later service
            // is a different service, so nothing we concluded about this one carries over.
            cancelTimeout()
            cancelPermissionTimeout()
            binding = false
            connectFailed = false
            serverUnusable = false
            service = null
            publish()
            return
        }
        if (!hasPermission()) {
            connectFailed = false
            publish()
            return
        }
        // Being authorised is itself proof the service reaches its app, whatever an earlier request
        // led us to believe.
        serverUnusable = false
        if (service == null && (force || !connectFailed)) bindService(force) else publish()
    }

    /** Push the current facts to the UI. The only place [setup] is ever written. */
    private fun publish() {
        val answering = serverAnswering()
        val running = answering && !serverUnusable
        _setup.value = Setup(
            installed = isInstalled(),
            serviceRunning = running,
            accessGranted = running && hasPermission(),
            connecting = binding,
            connectFailed = connectFailed,
            ready = service != null,
            serverUnusable = answering && serverUnusable,
        )
    }

    /**
     * Whether a Shizuku service is alive and has accepted us as a client.
     *
     * [Shizuku.pingBinder] is a real round trip to the service's process rather than a cached flag,
     * so it settles liveness on its own. What it can't settle is whether the handshake that follows
     * ever completed: a binder can arrive from a service too old to attach to, leaving every later
     * call throwing, so the version check has to be asked separately. Neither of these can tell an
     * orphaned service from a healthy one — both answer perfectly well — which is what
     * [serverUnusable] is for.
     */
    private fun serverAnswering(): Boolean =
        Shizuku.pingBinder() &&
            runCatching { !Shizuku.isPreV11() && Shizuku.getVersion() >= 11 }.getOrDefault(false)

    private fun isInstalled(): Boolean =
        try {
            appContext!!.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * Kick off Shizuku's authorisation prompt. Returns false when there's nothing to prompt with —
     * no service to ask, a pre-v11 server whose permission model we don't support, or an earlier
     * "deny and don't ask again". Those can only be undone inside the Shizuku app, so the caller
     * sends the user there instead.
     */
    fun requestPermission(): Boolean {
        if (!serverAnswering()) {
            refresh(force = false)
            return false
        }
        if (hasPermission()) {
            bindService(force = true)
            return true
        }
        val canPrompt = runCatching {
            !Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        if (canPrompt) {
            // Doubles as the only test there is for whether this service can still authorise anyone.
            // The request costs the user nothing extra — reaching here means they tapped "grant" and
            // a dialog is what they're already waiting for — so if none ever arrives, the silence
            // itself is the answer. See [Setup.serverUnusable].
            cancelPermissionTimeout()
            Shizuku.requestPermission(PERMISSION_CODE)
            permissionTimeoutJob = scope.launch {
                delay(PERMISSION_TIMEOUT_MS)
                if (!hasPermission()) {
                    serverUnusable = true
                    publish()
                }
            }
        }
        return canPrompt
    }

    private fun cancelPermissionTimeout() {
        permissionTimeoutJob?.cancel()
        permissionTimeoutJob = null
    }

    /**
     * Shizuku's permission check throws rather than returning false when it has no binder, and its
     * pre-v11 flag reads as "modern" until a binder has been received, so this is only safe to ask
     * behind a successful ping — and even then the binder can die in between.
     */
    private fun hasPermission(): Boolean =
        runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    private val serviceArgs: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(appContext!!.packageName, UserService::class.java.name)
        ).daemon(false)
            .processNameSuffix("mixaudio")
            .debuggable(false)
            // Bump whenever UserService's interface changes so Shizuku restarts a fresh service
            // instead of reusing a cached older one (v4 adds setRingerMode).
            .version(4)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cancelTimeout()
            binding = false
            if (binder != null && binder.pingBinder()) {
                service = IUserService.Stub.asInterface(binder)
                connectFailed = false
                publish()
            } else {
                bindFailed()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The privileged process went away. Don't sit on READY with a dead binder — every call
            // through it would silently do nothing.
            bindFailed()
        }
    }

    /**
     * Ask Shizuku for the privileged service.
     *
     * [force] additionally clears any user service record Shizuku is still holding for us before
     * asking. That matters because a record whose process died without Shizuku noticing — a
     * force-stopped app, a killed emulator, a spawn that failed — is never re-created on its own,
     * and every later bind against it silently goes unanswered. Ours is not a daemon, so nothing of
     * value can be on the other side of that record.
     */
    private fun bindService(force: Boolean) {
        if (service != null || (binding && !force)) {
            publish()
            return
        }
        cancelTimeout()
        binding = true
        connectFailed = false
        publish()
        if (force || !clearedStaleService) {
            clearedStaleService = true
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: Throwable) {
            bindFailed()
            return
        }
        // Shizuku answers a bind it can't satisfy with silence, so the spinner needs its own way out.
        timeoutJob = scope.launch {
            delay(BIND_TIMEOUT_MS)
            if (service == null) bindFailed()
        }
    }

    /**
     * A bind attempt is over without a usable service. Only count that as a failure if the way was
     * actually clear — Shizuku may have been stopped or de-authorised while we were waiting, and
     * the checklist should then point at that step rather than at a connection that never had a
     * chance.
     */
    private fun bindFailed() {
        cancelTimeout()
        binding = false
        service = null
        runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        connectFailed = serverAnswering() && hasPermission()
        publish()
    }

    private fun cancelTimeout() {
        timeoutJob?.cancel()
        timeoutJob = null
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

    /**
     * Set the device ringer mode through the privileged service, which reaches AudioService's
     * internal setter — the system volume panel's own path. Unlike the app's `AudioManager`, that
     * one can select SILENT without the framework turning Do Not Disturb on. [mode] is "NORMAL",
     * "VIBRATE" or "SILENT".
     *
     * Returning true means only that the command was *delivered*, not that it took effect: the
     * `set-ringer-mode` sub-command is recent, and a platform that doesn't carry it exits 0 with no
     * output rather than reporting anything, so there's nothing here to tell the two apart. Callers
     * must confirm against the real ringer mode and fall back on their own.
     */
    suspend fun setRingerMode(mode: String): Boolean =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext false
            try {
                svc.setRingerMode(mode)
                true
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
