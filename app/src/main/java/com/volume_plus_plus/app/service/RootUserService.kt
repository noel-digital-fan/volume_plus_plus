package com.volume_plus_plus.app.service

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService

/**
 * The root-mode host for [UserService].
 *
 * libsu launches this in a process running as root, exactly as Shizuku launches [UserService] in a
 * shell-uid one. Both routes end at the same `IUserService` binder over the same implementation, so
 * everything privileged — the `appops` and `cmd audio` commands, the hidden per-player volume APIs
 * and the `/proc` scan — behaves identically whichever backend the user picked.
 *
 * A [RootService] is a real [android.app.Service], so `this` is a usable [android.content.Context];
 * that matters because [UserService]'s player enumeration and per-player volume both need one.
 */
class RootUserService : RootService() {

    override fun onBind(intent: Intent): IBinder = UserService(this)
}
