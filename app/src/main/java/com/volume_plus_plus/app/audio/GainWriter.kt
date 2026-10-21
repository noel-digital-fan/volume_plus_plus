package com.volume_plus_plus.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One writer owns the backend. In-flight Binder calls finish before the newest target is sent. */
internal class GainWriter(
    scope: CoroutineScope,
    private val apply: suspend (Int) -> Boolean,
    private val onFailure: () -> Unit,
) {
    private data class Target(val generation: Long, val gain: Int)
    private val targets = Channel<Target>(Channel.CONFLATED)
    private var generation = 0L

    init {
        scope.launch {
            for (target in targets) {
                if (!apply(target.gain) && target.generation == generation) onFailure()
            }
        }
    }

    /** Called on the owning scope's thread, including for resets and shutdown. */
    fun submit(gain: Int) {
        targets.trySend(Target(++generation, BoostPolicy.clampGain(gain)))
    }
}
