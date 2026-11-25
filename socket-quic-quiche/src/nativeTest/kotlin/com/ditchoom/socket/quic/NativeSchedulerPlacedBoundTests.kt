package com.ditchoom.socket.quic

import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A bound placed on a scheduler of the caller's (a [StreamDeadlineScheduling] in its context) leaves the shared
 * timer's queue as it found it: nothing was armed on the timer, so nothing is held or owed a sweep there.
 */
class NativeSchedulerPlacedBoundTests {
    @Test
    fun completedBoundsPlacedOnACallersSchedulerLeaveTheSharedQueueUntouched() =
        runBlocking {
            val queue = NativeDeadlineTimer.Shared.queue
            val before = queue.size
            withContext(StreamDeadlineScheduling(StreamDeadlineScheduler { _, _ -> DisposableHandle { } })) {
                repeat(BOUNDS) { withStreamDeadline(30.seconds) { } }
            }
            val held = queue.size - before
            assertTrue(
                held < BOUNDS / 10,
                "$BOUNDS bounds placed on the caller's scheduler left $held nodes counted on the shared timer's queue",
            )
            assertTrue(
                !queue.needsSweep() || before > 0,
                "the shared timer's queue asks for a sweep with only caller-scheduled bounds behind it",
            )
        }

    private companion object {
        const val BOUNDS = 1000
    }
}
