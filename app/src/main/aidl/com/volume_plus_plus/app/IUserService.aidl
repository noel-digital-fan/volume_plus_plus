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
}
