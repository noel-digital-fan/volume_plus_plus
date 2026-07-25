package com.volume_plus_plus.app.overlay

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.volume_plus_plus.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the *persistent* per-app volume state, independent of whether the floating panel is open.
 *
 * ## Why this exists
 *
 * A per-app volume is applied by [ShizukuManager.setPlayerVolume] to a single player instance id
 * (piid) via the hidden `PlayerProxy.setVolume`. A piid is ephemeral: whenever an app pauses and
 * resumes — which routinely happens across a screen off/on, a lock/unlock, or any transient
 * interruption — the app usually releases its old player and registers a fresh one with a *new*
 * piid at the default (full) volume, so a level the user had set silently reverts. Previously the
 * chosen level only ever lived in the on-screen slider view, which the panel discards when it
 * auto-hides, so nothing ever re-applied it. That is the "volume resets after screen off/on" bug.
 *
 * ## What it does
 *
 * It remembers the chosen volume per package for the lifetime of a *playback session* and keeps
 * re-applying it to whatever players that package currently has. A session:
 *  - **starts** the first time a package appears among the active players with no live session —
 *    its volume defaults to full, so a genuinely new playback always starts at the maximum;
 *  - **persists** across screen off/on, lock/unlock, app switches, backgrounding, configuration
 *    changes and transient pauses — anything where the app's process stays alive — re-applying the
 *    remembered volume to each new piid as players churn;
 *  - **ends** only when the app's process is gone (swiped from Recents, force-stopped or otherwise
 *    terminated), after which the next playback is a fresh session at full volume again.
 *
 * A still-alive process that simply isn't playing is not surfaced as active (no slider, nothing to
 * re-apply) but its remembered volume is kept, so resuming playback restores it rather than
 * resetting. Process liveness is what distinguishes "closed" from "backgrounded", and it is read
 * from the privileged service ([ShizukuManager.isPackageRunning]) because the app's own UID can't
 * see other processes.
 *
 * The re-apply loop runs while anything is playing or any session is still being tracked, and idles
 * otherwise; the OS playback callback wakes it when the next player starts. Everything here touches
 * [sessions] on the main thread only (the coroutine scope is [Dispatchers.Main]; the privileged
 * calls suspend onto IO internally), so no locking is needed.
 *
 * Per-app playback control needs the Android 8+ playback-configuration APIs, so on older releases
 * this controller stays inert (there are no per-app players to manage anyway).
 */
class AppVolumeController(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** One package's live playback session. */
    private class Session(var volume: Float) {
        /** piid -> the volume value last pushed to that player, so we only write on a real change. */
        val applied = HashMap<Int, Float>()
    }

    /** Insertion-ordered so the memory cap can evict the oldest dormant session first. */
    private val sessions = LinkedHashMap<String, Session>()

    private var running = false
    private var polling = false
    /** True while the next scheduled tick is on the slow (dormant) cadence, so a playback change can
     *  bring it forward — a resume shouldn't wait the full dormant interval to be re-applied. */
    private var dormantWait = false
    private var syncJob: Job? = null
    private val pollRunnable = Runnable { sync() }

    /** Set in [start] (Android 8+ only); wakes the loop when device playback changes so a new
     *  session is picked up promptly even while the panel is closed. */
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────────────

    fun start() {
        if (running || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        running = true
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                wake()
            }
        }
        playbackCallback = cb
        runCatching { audioManager.registerAudioPlaybackCallback(cb, handler) }
        ensurePolling()
    }

    fun stop() {
        running = false
        polling = false
        handler.removeCallbacks(pollRunnable)
        syncJob?.cancel()
        syncJob = null
        playbackCallback?.let { cb -> runCatching { audioManager.unregisterAudioPlaybackCallback(cb) } }
        playbackCallback = null
        sessions.clear()
    }

    fun destroy() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── panel-facing API (main thread) ──────────────────────────────────────────────────────────────

    /**
     * The remembered volume for [pkg]'s current session, or full if it has none — a fresh session
     * always starts at maximum. Used to initialise a per-app slider so it reflects the level the
     * user set earlier this session instead of snapping back to full every time the panel opens.
     */
    fun volumeFor(pkg: String): Float = sessions[pkg]?.volume ?: DEFAULT_VOLUME

    /**
     * Record and apply a user-chosen [level] for [pkg]. Creates the session if needed (so a volume
     * set the instant an app starts is remembered), applies it to [piids] now, and lets the poll
     * loop keep it applied to any players that appear later this session.
     */
    fun setVolume(pkg: String, level: Float, piids: List<Int>) {
        val session = sessions.getOrPut(pkg) { Session(level) }
        session.volume = level
        // A changed target must be re-pushed to every current player, so forget prior applications.
        session.applied.clear()
        scope.launch {
            for (piid in piids) {
                if (ShizukuManager.setPlayerVolume(piid, level)) session.applied[piid] = level
            }
        }
        ensurePolling()
    }

    // ── re-apply loop ───────────────────────────────────────────────────────────────────────────────

    private fun ensurePolling() {
        if (!running || polling) return
        polling = true
        dormantWait = false
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    /**
     * A device playback change fired: start the loop if idle, or — if it was only waiting on the slow
     * dormant cadence — bring the next pass forward, since a change while dormant is most likely a
     * paused app resuming and we want to re-apply its volume promptly rather than after the interval.
     */
    private fun wake() {
        if (!running) return
        if (!polling) { ensurePolling(); return }
        if (dormantWait) {
            dormantWait = false
            handler.removeCallbacks(pollRunnable)
            handler.postDelayed(pollRunnable, WAKE_DELAY_MS)
        }
    }

    private fun sync() {
        handler.removeCallbacks(pollRunnable)
        if (!running || !ShizukuManager.isReady) {
            // Nothing we can do without the privileged link; retry shortly while we still hold state.
            if (running && sessions.isNotEmpty()) {
                dormantWait = true
                handler.postDelayed(pollRunnable, POLL_DORMANT_MS)
            } else {
                polling = false
            }
            return
        }
        syncJob?.cancel()
        syncJob = scope.launch {
            val playing = queryPlayers()
            reconcile(playing)
            // Keep polling while anything is playing or any session is still tracked; otherwise idle
            // and let the playback callback wake us when the next player starts.
            when {
                !running -> polling = false
                playing.isNotEmpty() -> { dormantWait = false; handler.postDelayed(pollRunnable, POLL_ACTIVE_MS) }
                sessions.isNotEmpty() -> { dormantWait = true; handler.postDelayed(pollRunnable, POLL_DORMANT_MS) }
                else -> polling = false
            }
        }
    }

    /** Currently-playing packages mapped to their player instance ids (empty when nothing audible). */
    private suspend fun queryPlayers(): Map<String, List<Int>> {
        // isMusicActive() reflects real output, so a "started" player an idle app keeps registered
        // (e.g. some social apps) doesn't count as playback the user is listening to.
        if (!audioManager.isMusicActive || !ShizukuManager.isReady) return emptyMap()
        return ShizukuManager.getActivePlayers()
            .filter { it.packageName != appContext.packageName }
            .groupBy { it.packageName }
            .mapValues { (_, players) -> players.map { it.piid } }
    }

    /**
     * Bring [sessions] in line with what's [playing]: start/refresh sessions for playing packages
     * and re-apply their remembered volume to any player that doesn't already have it; drop sessions
     * for packages whose process has gone, and keep (but don't enforce) those merely paused.
     */
    private suspend fun reconcile(playing: Map<String, List<Int>>) {
        for ((pkg, piids) in playing) {
            val session = sessions.getOrPut(pkg) { Session(DEFAULT_VOLUME) }
            // Forget piids that are no longer present so `applied` can't grow without bound.
            session.applied.keys.retainAll(piids.toHashSet())
            // Only a custom (below-full) level needs enforcing; a full session leaves the OS default
            // alone, so we never fight the app by forcing it to max.
            if (session.volume < DEFAULT_VOLUME) {
                for (piid in piids) {
                    if (session.applied[piid] == session.volume) continue
                    if (ShizukuManager.setPlayerVolume(piid, session.volume)) {
                        session.applied[piid] = session.volume
                    }
                }
            }
        }

        // Dormant packages (a session but not currently playing). Keep the memory while the app's
        // process is alive — screen off, backgrounded, paused — so resuming restores the level; drop
        // it once the app is gone (closed / force-stopped) so the next playback starts fresh at full.
        val dormant = sessions.keys.filter { it !in playing }
        for (pkg in dormant) {
            if (!ShizukuManager.isPackageRunning(pkg)) {
                sessions.remove(pkg)
            } else {
                // Alive but silent: clear applied piids so a resume re-pushes to whatever new players
                // appear, and keep the remembered volume untouched.
                sessions[pkg]?.applied?.clear()
            }
        }

        // Safety net for devices where the liveness check can't read the process table: bound the
        // map by evicting the oldest sessions that aren't currently playing.
        if (sessions.size > MAX_SESSIONS) {
            val it = sessions.entries.iterator()
            while (sessions.size > MAX_SESSIONS && it.hasNext()) {
                if (it.next().key !in playing) it.remove()
            }
        }
    }

    private companion object {
        const val DEFAULT_VOLUME = 1f

        /** How often to re-check while something is playing (matches the panel's own app sync). */
        const val POLL_ACTIVE_MS = 1500L

        /** Slower cadence while only dormant sessions remain — we're just waiting to notice the app
         *  either resume (the callback also wakes us) or its process die, neither of which is urgent. */
        const val POLL_DORMANT_MS = 4000L

        /** Short delay after a playback change preempts a dormant wait, so a resume is re-applied
         *  almost at once while still coalescing a burst of config-change callbacks into one pass. */
        const val WAKE_DELAY_MS = 150L

        /** Cap on remembered sessions, so a stuck liveness check can't leak them without bound. */
        const val MAX_SESSIONS = 12
    }
}
