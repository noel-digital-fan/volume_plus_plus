package com.volume_plus_plus.app.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class GainWriterTest {
    @Test fun loweringTheLimitFollowsAnInFlightHigherGain() = checkLatestTarget(600)
    @Test fun resetFollowsAnInFlightHigherGain() = checkLatestTarget(0)

    @Test fun theCurrentFailureIsReported() = runBlocking {
        withTimeout(3000) {
            val job = Job()
            try {
                val failure = CompletableDeferred<Unit>()
                val writer = GainWriter(CoroutineScope(coroutineContext + job),
                    apply = { false }, onFailure = { failure.complete(Unit) })
                writer.submit(600)
                failure.await()
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    private fun checkLatestTarget(latest: Int) = runBlocking {
        withTimeout(3000) {
            val job = Job()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val writes = mutableListOf<Int>()
                val writer = GainWriter(CoroutineScope(coroutineContext + job), apply = { gain ->
                    if (gain == 1500) {
                        entered.complete(Unit)
                        release.await()
                    }
                    writes.add(gain)
                    if (gain == latest) finished.complete(Unit)
                    true
                }, onFailure = { error("Unexpected backend failure") })
                writer.submit(1500)
                entered.await()
                writer.submit(1200)
                writer.submit(latest)
                release.complete(Unit)
                finished.await()
                assertEquals(listOf(1500, latest), writes)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun anObsoleteFailureDoesNotInvalidateANewerSuccessfulTarget() = runBlocking {
        withTimeout(3000) {
            val job = Job()
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                var failures = 0
                val writer = GainWriter(CoroutineScope(coroutineContext + job), apply = { gain ->
                    if (gain > 0) {
                        entered.complete(Unit)
                        release.await()
                        false
                    } else {
                        finished.complete(Unit)
                        true
                    }
                }, onFailure = { failures++ })
                writer.submit(1500)
                entered.await()
                writer.submit(0)
                release.complete(Unit)
                finished.await()
                assertEquals(0, failures)
            } finally {
                job.cancelAndJoin()
            }
        }
    }
}
