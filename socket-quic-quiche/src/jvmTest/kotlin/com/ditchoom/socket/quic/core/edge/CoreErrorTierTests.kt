package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The `Error` tiers on a core connection, on the JVM: an `Error` is not contained. It ends the connection and is reported
 * on the thread it was thrown on, the loop's or a caller's, through that thread's uncaught-error handler.
 */
class CoreErrorTierTests {
    @Test
    fun anErrorFromConnectionProcessingIsNotContained() =
        withFailureRig("CoreErrorTierTests.anErrorFromConnectionProcessingIsNotContained") { rig ->
            val planted = PlantedError()
            val reported =
                reportedOnTheLoop {
                    rig.conn.submit(ThrowingWork(planted))
                    val end = assertIs<ConnEnd.LoopFailed>(rig.ended())
                    assertSame(planted, end.thrown.throwable)
                }
            assertSame(planted, reported, "the Error was reported on the loop's thread, not contained")
        }

    @Test
    fun anErrorInsideACallerEndsThatConnectionIsReportedAndTheCallerLeaves() =
        withFailureRig("CoreErrorTierTests.anErrorInsideACallerEndsThatConnectionIsReportedAndTheCallerLeaves") { rig ->
            val planted = PlantedError()
            val reported = CompletableFuture<Throwable>()
            runOnAReportingCaller(rig, reported) { OnACaller(planted) }

            val end = assertIs<ConnEnd.CallerFailed>(rig.ended())
            assertSame(planted, end.thrown.throwable)
            assertSame(planted, reported.get(5, TimeUnit.SECONDS), "the Error was reported on the caller's thread")
            val later = LateWork()
            rig.conn.submit(later, CallerPlace.MayEnter)
            assertEquals(1, withTimeout(5.seconds) { later.gone.await() }, "the caller left: later work is answered as gone")
            assertTrue(rig.runOnLoop() > 0, "the loop kept running")
        }

    /** [runOnACaller] on threads whose uncaught `Error` is completed into [reported]. */
    private suspend fun runOnAReportingCaller(
        rig: FailureRig,
        reported: CompletableFuture<Throwable>,
        make: () -> OnACaller,
    ) {
        repeat(100) {
            val work = make()
            val caller = Thread { rig.conn.submit(work, CallerPlace.MayEnter) }
            caller.setUncaughtExceptionHandler { _, error -> reported.complete(error) }
            caller.start()
            caller.join(10_000)
            withTimeout(10.seconds) { work.settled.await() }
            if (work.ranOnACaller) return
        }
        throw AssertionError("the work never ran on a caller")
    }
}

private class PlantedError : Error()

/**
 * Runs [body] with the process's default uncaught-exception handler replaced, and answers what a loop thread reported
 * through it: an `Error` is not contained, and on Android the default handler would end the test process.
 */
private suspend fun reportedOnTheLoop(body: suspend () -> Unit): Throwable {
    val reported = CompletableFuture<Throwable>()
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        if (thread.name.startsWith(LOOP_THREAD)) reported.complete(error) else previous?.uncaughtException(thread, error)
    }
    try {
        body()
        return reported.get(5, TimeUnit.SECONDS)
    } finally {
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }
}
