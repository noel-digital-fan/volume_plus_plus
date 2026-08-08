package com.volume_plus_plus.app.service

import android.content.Context
import android.media.AudioManager
import com.volume_plus_plus.app.IUserService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku-spawned process (shell UID when Shizuku is started via ADB).
 * That process is allowed to run `appops set <pkg> TAKE_AUDIO_FOCUS ...` and to call the
 * hidden per-player volume APIs, which the app's own UID is not. Everything privileged
 * happens here.
 *
 * Shizuku instantiates this via either the [Context] constructor or the no-arg one, so
 * both must exist; the Context is needed for the audio/package services.
 */
class UserService() : IUserService.Stub() {

    private var context: Context? = null

    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun setAudioFocusMode(packageName: String, ignore: Boolean): String {
        val mode = if (ignore) "ignore" else "allow"
        return runCommand(arrayOf("appops", "set", packageName, "TAKE_AUDIO_FOCUS", mode))
    }

    override fun getAudioFocusMode(packageName: String): String {
        return runCommand(arrayOf("appops", "get", packageName, "TAKE_AUDIO_FOCUS"))
    }

    /**
     * Ringer mode via AudioService's *internal* setter, which is what `cmd audio set-ringer-mode`
     * reaches — the path the system volume panel itself uses, and the only one that can select
     * SILENT without the framework switching Do Not Disturb on (see the AIDL doc). The sub-command
     * is recent, so on older platforms this comes back as the shell's own error text and the caller
     * falls back.
     */
    override fun setRingerMode(mode: String): String {
        return runCommand(arrayOf("cmd", "audio", "set-ringer-mode", mode))
    }

    /**
     * Enumerates active playback via the public [AudioManager.getActivePlaybackConfigurations],
     * then reflects the hidden per-config accessors (piid, client uid) — reachable here because
     * the shell UID is exempt from the non-SDK interface blocklist. Requires Android 8+ (the
     * config API) and realistically Android 13+ for the player-proxy volume to take effect.
     */
    override fun getActivePlayers(): List<String> {
        val ctx = context ?: return emptyList()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.activePlaybackConfigurations.mapNotNull { cfg ->
                // Some builds keep paused/idle players in the active list; require the STARTED state
                // so we only ever surface players that are genuinely running. Absent method → keep.
                val state = cfg.invokeInt("getPlayerState")
                if (state != null && state != PLAYER_STATE_STARTED) return@mapNotNull null
                // Only real media/game playback — skip notification, alarm, ringtone and assistance
                // sonification players, which aren't something the user is "listening to".
                val usage = cfg.playerUsage()
                if (usage != null && usage !in AUDIBLE_USAGES) return@mapNotNull null
                val piid = cfg.invokeInt("getPlayerInterfaceId") ?: return@mapNotNull null
                val uid = cfg.invokeInt("getClientUid") ?: return@mapNotNull null
                val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
                    ?: return@mapNotNull null
                "$piid|$uid|$pkg"
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    override fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        val ctx = context ?: return false
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cfg = am.activePlaybackConfigurations
                .firstOrNull { it.invokeInt("getPlayerInterfaceId") == piid } ?: return false
            val proxy = cfg.javaClass.getMethod("getPlayerProxy").invoke(cfg) ?: return false
            proxy.javaClass
                .getMethod("setVolume", Float::class.javaPrimitiveType)
                .invoke(proxy, volume.coerceIn(0f, 1f))
            true
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Whether a process for [packageName] is currently alive, by scanning `/proc` for a matching
     * process name. Android names an app's main process after its package ("com.pkg", or
     * "com.pkg:suffix" for extra processes), so a match on the package portion means the app is
     * still running. When no command line can be read at all (some builds hide other processes even
     * from the shell), returns true so a live per-app session is never discarded on a false "gone".
     */
    override fun isPackageRunning(packageName: String): Boolean {
        return try {
            val dirs = File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
                ?: return true
            var readAny = false
            for (dir in dirs) {
                val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull()
                if (cmdline.isNullOrEmpty()) continue
                readAny = true
                // cmdline is a NUL-separated argv; argv[0] (up to the first NUL) is the process
                // name. Compare on the package part before any ':' process suffix.
                val name = cmdline.takeWhile { it.code != 0 }.substringBefore(':')
                if (name == packageName) return true
            }
            // Fell through with no match: only trust that as "gone" if we could actually read the
            // process table; otherwise assume alive rather than reset a session we can't verify.
            !readAny
        } catch (e: Throwable) {
            true
        }
    }

    /** Reflectively call a no-arg int getter (a hidden method) on [this]. */
    private fun Any.invokeInt(method: String): Int? =
        try {
            javaClass.getMethod(method).invoke(this) as? Int
        } catch (e: Throwable) {
            null
        }

    /** The [android.media.AudioAttributes] usage of a playback config, or null if unavailable. */
    private fun Any.playerUsage(): Int? =
        try {
            val attrs = javaClass.getMethod("getAudioAttributes").invoke(this) ?: return null
            attrs.javaClass.getMethod("getUsage").invoke(attrs) as? Int
        } catch (e: Throwable) {
            null
        }

    private companion object {
        /** AudioPlaybackConfiguration.PLAYER_STATE_STARTED — the player is actively running. */
        const val PLAYER_STATE_STARTED = 2

        /**
         * AudioAttributes usages that represent media the user is actually listening to
         * (USAGE_UNKNOWN, USAGE_MEDIA, USAGE_GAME, USAGE_ASSISTANT). Everything else — notifications,
         * alarms, ringtones, assistance sonification — is excluded from the per-app list.
         */
        val AUDIBLE_USAGES = setOf(0, 1, 14, 16)
    }

    private fun runCommand(cmd: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(cmd)
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { output.append(it).append('\n') }
            }
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                reader.forEachLine { output.append(it).append('\n') }
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
