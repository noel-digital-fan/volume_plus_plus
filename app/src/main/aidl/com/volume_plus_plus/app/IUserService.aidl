// IUserService.aidl
package com.volume_plus_plus.app;

/**
 * Privileged interface implemented by {@code UserService}, which Shizuku runs in a
 * shell-uid (ADB) process. From that process the {@code appops} command is allowed to
 * change other apps' operation modes, which the app's own UID cannot do.
 */
interface IUserService {

    // Reserved transaction id required by the Shizuku server to tear down the service.
    void destroy() = 16777114;

    void exit() = 1;

    /**
     * Sets the TAKE_AUDIO_FOCUS app-op for {@code packageName}. When ignored, the app
     * can no longer grab audio focus, so it stops pausing other players (audio mixing).
     * Returns the raw command output for diagnostics.
     */
    String setAudioFocusMode(String packageName, boolean ignore) = 2;

    /** Returns the raw {@code appops get} output for TAKE_AUDIO_FOCUS on the package. */
    String getAudioFocusMode(String packageName) = 3;

    /**
     * Active audio players, one entry per currently-playing stream, encoded as
     * "piid|uid|packageName". Uses hidden AudioManager/AudioPlaybackConfiguration APIs that are
     * only reachable from this shell-uid process. Empty list if none / on error.
     */
    List<String> getActivePlayers() = 4;

    /**
     * Sets the linear volume (0.0..1.0) of the player identified by {@code piid} via the hidden
     * PlayerProxy.setVolume API. Returns true on success.
     */
    boolean setPlayerVolume(int piid, float volume) = 5;

    /**
     * Whether any process for {@code packageName} is currently alive. Read from the shell-uid
     * process, which can see the full process table (the app's own UID cannot). Used to tell a
     * still-open app (screen off, backgrounded, paused) from one the user closed or force-stopped,
     * so a custom per-app volume survives the former but resets for the latter. Returns true when
     * the process table can't be read, so an unreadable {@code /proc} never wrongly discards a
     * live session.
     */
    boolean isPackageRunning(String packageName) = 6;

    /**
     * Sets the device ringer mode to {@code mode} ("NORMAL", "VIBRATE" or "SILENT") via
     * {@code cmd audio set-ringer-mode}, which lands on AudioService's internal setter — the same
     * one the system volume panel uses. That matters for SILENT: the app's own
     * {@code AudioManager.setRingerMode} is the *external* path, whose zen helper switches Do Not
     * Disturb on, while the internal one leaves DND completely alone. Returns the raw command
     * output; the sub-command only exists on newer platforms, so callers must handle failure.
     */
    String setRingerMode(String mode) = 7;

    /**
     * Reinstalls this app's own package in place with its install source set to
     * {@code installerPackage} (e.g. "com.android.vending", the Play Store), clearing the
     * "installed from an unofficial app store" flag some banking apps raise against a sideloaded
     * build. It is a same-signature, same-version in-place update, so app data, granted permissions
     * and the enabled accessibility service are all preserved. The platform kills this app's own UI
     * process when the update commits — expected, and unavoidable for a base reinstall — while this
     * shell/root process runs under a different UID and survives to finish. Returns the raw
     * {@code pm} output ("Success" on success).
     */
    String setInstallerSource(String installerPackage) = 8;

    /**
     * Applies a global media loudness boost of {@code gainMillibels} (0 = off, which also releases
     * the effect and is what callers use to probe whether boosting works here at all) by attaching
     * a {@code LoudnessEnhancer} to the global output mix from this process.
     *
     * The app's own UID cannot do this. Attaching an effect to the output mix (session 0) is gated
     * on MODIFY_AUDIO_ROUTING, a signature-level permission: root holds it, because the framework
     * grants root and system every permission outright, while the shell UID Shizuku runs as holds
     * only whatever {@code com.android.shell} was built with, which varies by OEM. So this can
     * legitimately fail even with the service bound, and callers must report the booster as
     * unavailable rather than draw a control that does nothing.
     *
     * Returns "" on success, otherwise a diagnostic beginning with "ERROR".
     */
    String setLoudnessGain(int gainMillibels) = 9;

    /**
     * Every package whose TAKE_AUDIO_FOCUS app-op is currently set to ignore, read
     * with a single {@code appops query-op} call. This is the bulk counterpart to
     * {@link #getAudioFocusMode}: the mixing list is one switch per installed app, so
     * reconciling its switches against the real op state can't afford one spawned
     * process per row. Empty list if nothing is ignored or on error.
     */
    List<String> getAudioFocusIgnoredPackages() = 10;
}
