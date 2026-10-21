package com.volume_plus_plus.app.overlay

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import com.volume_plus_plus.app.audio.BoostPolicy
import com.volume_plus_plus.app.audio.GainWriter
import com.volume_plus_plus.app.data.OverlayPrefs
import com.volume_plus_plus.app.privileged.PrivilegedManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The media booster: extra gain applied on top of a stream already at its maximum, so the volume
 * keys can go past 100%.
 *
 * ## Why a singleton
 *
 * The boost is a single device-global audio effect. Two of them stack *multiplicatively* — ask for
 * +6 dB twice and the device plays at +12 — so there must be exactly one, and it must outlive
 * whoever asked for it. An [OverlayController] cannot own it, because two controllers legitimately
 * exist at once: the accessibility service's, lent out through [PanelLauncher], and the one
 * [PanelLauncher] builds for the Quick Settings tile when that service isn't running. [PanelLauncher]
 * is already a singleton for the same "exactly one, outlives its caller" reason.
 *
 * ## Why it can be unavailable
 *
 * Attaching an effect to the global output mix is gated on `MODIFY_AUDIO_ROUTING`, a signature-level
 * permission the app's own UID cannot hold. Older platforms allow it anyway (with a deprecation
 * warning); newer ones refuse, and then the only route is the privileged process, where root passes
 * the check outright and the shell UID Shizuku runs as may or may not. So availability is a property
 * of the device, probed once at [init] rather than discovered at the first key press — that way the
 * settings switch can say honestly that a device can't do this, and the bar is never drawn dead.
 *
 * ## Loudness
 *
 * "120%" is a loudness label, not an amplitude one. Read literally it would mean 1.2x the signal,
 * which is +1.6 dB — barely audible, and the feature would be pointless. Each 5% notch is instead
 * [BoostPolicy.GAIN_PER_STEP_MB]. The default ceiling is +6 dB (120%); the user may raise it
 * to +15 dB (150%). These are target gains, not guaranteed increases in measured loudness.
 */
object MediaBooster {

    private val listeners = mutableSetOf<() -> Unit>()

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyStateChanged() { listeners.toList().forEach { it() } }

    /** How the gain is being applied, or why it can't be. */
    enum class Availability { UNKNOWN, IN_PROCESS, PRIVILEGED, UNAVAILABLE }

    /** Whether a key press was claimed by the booster or should drive the stream as usual. */
    enum class Verdict { PASS, CONSUMED }

    @Volatile
    var availability: Availability = Availability.UNKNOWN
        private set(value) {
            field = value
            notifyStateChanged()
        }

    var maxSteps: Int = BoostPolicy.DEFAULT_MAX_STEPS
        private set

    /** Boost notches applied, 0..[maxSteps]. 0 means no boost and no bar. */
    var steps: Int = 0
        private set

    val active: Boolean get() = steps > 0
    /** 0f..1f across the boost range, for the bar's fill. */
    val fraction: Float get() = steps / maxSteps.toFloat()
    /** The display scale, independent of the selected ceiling. */
    val percent: Int get() = BoostPolicy.percent(steps)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var effect: LoudnessEnhancer? = null
    private val gainWriter = GainWriter(scope, apply = { target ->
        val success = pushGain(target)
        if (!success && target > 0) pushGain(0)
        success
    }, onFailure = {
        steps = 0
        availability = Availability.UNAVAILABLE
    })

    /** Changing the ceiling never raises the active gain. Call on the main thread. */
    fun setMaxSteps(value: Int) {
        val limit = BoostPolicy.normalizeMaxSteps(value)
        if (maxSteps == limit) return
        maxSteps = limit
        armCount = 0
        if (steps > limit) {
            steps = limit
            applyGain()
        } else {
            notifyStateChanged()
        }
    }

    /** Consecutive raises at maximum volume that haven't yet engaged the boost, and when the last
     *  one landed — the "press Volume Up twice at 100%" arming. */
    private var armCount = 0
    private var lastArmAt = 0L

    /**
     * A gain chosen for the phone speaker is a hazard the moment headphones go in, and the framework
     * can tear a global-mix effect down across a route change anyway, which would leave the bar
     * showing a boost that is no longer applied. Drop it either way.
     */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reset()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reset()
    }
    private var callbackRegistered = false
    private var probing = false

    private fun requestProbe() {
        if (probing) return
        probing = true
        scope.launch {
            try {
                probe()
            } finally {
                probing = false
            }
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────────────────────────

    /** Idempotent. Call on the main thread; availability is probed off the main thread. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        setMaxSteps(OverlayPrefs(app).getMediaBoostMaxSteps())
        if (audioManager == null) {
            audioManager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        if (!callbackRegistered) {
            runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, handler) }
                .onSuccess { callbackRegistered = true }
        }
        if (availability == Availability.UNKNOWN) requestProbe()
    }

    /**
     * Drop the boost and let the effect go. Safety-critical on the way out: the gain lives in the
     * audio server, not in this process, so a boost left applied would outlast the app with no UI
     * left anywhere to undo it.
     */
    fun shutdown() {
        steps = 0
        armCount = 0
        // Push zero *before* releasing — releasing alone doesn't guarantee the privileged process
        // drops its own enhancer.
        gainWriter.submit(0)
        notifyStateChanged()
        if (callbackRegistered) {
            runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
            callbackRegistered = false
        }
    }

    /** Drop any boost immediately — a route change, the stream falling below maximum, a reconnect. */
    fun reset() {
        armCount = 0
        if (steps == 0) return
        steps = 0
        applyGain()
    }

    /** The stream is no longer at its maximum, so a boost on top of it makes no sense. */
    fun validate(atMax: Boolean) {
        if (!atMax && steps > 0) reset()
    }

    // ── key handling ──────────────────────────────────────────────────────────────────────────────

    /**
     * Offer a volume key press to the booster before it reaches the stream. [atMax] is whether the
     * media stream is already at its maximum, read *before* any write.
     *
     * A held key never advances the boost. The repeat accelerates to a step every 40 ms, so a hold
     * from halfway up would cross 100%, burn both arming presses and every notch in about a quarter
     * of a second and land at full boost — which is exactly what the warning on the switch exists to
     * prevent, and acknowledging that warning once is not consent to it happening by accident.
     * Unwinding on a held key *is* allowed: the risk is only in going up, and a held Volume Down that
     * stalled at the top for four ticks before descending would just feel broken.
     */
    fun handleKey(direction: Int, atMax: Boolean, fromAutoRepeat: Boolean): Verdict {
        if (availability == Availability.UNAVAILABLE || availability == Availability.UNKNOWN) {
            return Verdict.PASS
        }

        if (direction < 0) {
            armCount = 0
            if (steps == 0) return Verdict.PASS
            steps--
            applyGain()
            return Verdict.CONSUMED
        }

        if (!atMax || fromAutoRepeat) {
            // Not at the top, or the key is being held: either way this press can't arm anything,
            // and a hold that arrives at 100% must leave the user needing two fresh presses.
            armCount = 0
            return Verdict.PASS
        }
        if (steps > 0) {
            if (steps >= maxSteps) return Verdict.CONSUMED
            steps++
            applyGain()
            return Verdict.CONSUMED
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastArmAt > ARM_WINDOW_MS) armCount = 0
        armCount++
        lastArmAt = now
        if (armCount < ARM_PRESSES) {
            // The arming press stays invisible, which is what a press at maximum already does today:
            // nothing but keep the panel on screen.
            return Verdict.PASS
        }
        armCount = 0
        steps = 1
        applyGain()
        return Verdict.CONSUMED
    }

    // ── gain ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Push the current [steps] as a gain. [steps] has already moved, so the bar redraws within the
     * frame; the audio server call is a binder round trip that can take tens of milliseconds, so it
     * happens off the key path and only the latest target matters.
     */
    private fun applyGain() {
        gainWriter.submit(BoostPolicy.gain(steps))
        notifyStateChanged()
    }

    private suspend fun pushGain(millibels: Int): Boolean {
        // Zero must still release both possible backends after a failure or disconnect.
        if (millibels == 0) {
            withContext(Dispatchers.IO) { releaseInProcess() }
            return !PrivilegedManager.isReady || PrivilegedManager.setLoudnessGain(0)
        }
        return when (availability) {
            Availability.IN_PROCESS -> withContext(Dispatchers.IO) {
                runCatching {
                    val fx = effect ?: LoudnessEnhancer(GLOBAL_SESSION).also {
                        it.enabled = true
                        effect = it
                    }
                    fx.setTargetGain(millibels)
                }.isSuccess
            }

            Availability.PRIVILEGED -> PrivilegedManager.setLoudnessGain(millibels)
            else -> false
        }
    }

    private fun releaseInProcess() {
        runCatching { effect?.release() }
        effect = null
    }

    /**
     * Which route can apply a gain here, if any. The in-process attempt costs one try/catch and
     * succeeds on older platforms and on a system-partition install; everywhere else the privileged
     * process is the only way, and `setLoudnessGain(0)` doubles as its probe by creating the effect
     * and immediately dropping it.
     */
    private suspend fun probe() {
        val inProcess = withContext(Dispatchers.IO) {
            runCatching {
                LoudnessEnhancer(GLOBAL_SESSION).also {
                    it.setTargetGain(0)
                    it.enabled = true
                    it.release()
                }
            }.isSuccess
        }
        availability = when {
            inProcess -> Availability.IN_PROCESS
            PrivilegedManager.isReady && PrivilegedManager.setLoudnessGain(0) ->
                Availability.PRIVILEGED

            else -> Availability.UNAVAILABLE
        }
    }

    /**
     * Re-run the probe. The privileged link may have come up since — the user can enable Shizuku or
     * root at any point — and a device that could not boost a moment ago may be able to now.
     */
    fun refreshAvailability() {
        if (availability == Availability.PRIVILEGED || availability == Availability.IN_PROCESS) return
        availability = Availability.UNKNOWN
        requestProbe()
    }

    private const val GLOBAL_SESSION = 0

    /** Raises at maximum needed to engage the boost — "press Volume Up twice at 100%". */
    private const val ARM_PRESSES = 2

    /**
     * How long an arming press stays live, matched to the panel's own auto-hide: the press only
     * counts for as long as the panel it raised is still on screen. Press up at maximum, walk away,
     * come back an hour later and press again, and that is arm #1 again — not an instant boost.
     */
    private const val ARM_WINDOW_MS = 4000L
}
