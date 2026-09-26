package com.ditchoom.socket.iouring

import com.ditchoom.socket.iouring.linux.io_uring_prep_nop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EINVAL
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * The poller's life when a ring cannot be set up while a stop and a restart interleave with the
 * withdrawal. [GatedRefusal] holds the first life inside ring setup until the test releases it, so each
 * interleaving happens in the order written rather than by chance.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringPollerLifecycleTests {
    /**
     * A stop that observed a life whose setup then failed does not stop the loop a later operation
     * started: the life it names is gone, and the new one is not its to end.
     */
    @Test
    fun aStopOfAWithdrawnLifeLeavesTheRestartedLoopRunning() =
        runBlocking {
            IoUringManager.cleanup()
            val gate = GatedRefusal(IoUringManager.queueInit.value)
            IoUringManager.queueInit.value = gate.init
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { nop() } }
                withTimeout(WAIT) { gate.entered.await() }
                val withdrawn = assertIs<PollerState.Running>(IoUringManager.pollerState)

                gate.release()
                val failure = withTimeout(WAIT) { first.await() }.exceptionOrNull()
                assertIs<IoUringFailure>(failure, "an op queued for a life whose ring was refused fails typed")

                val restart = async(start = CoroutineStart.UNDISPATCHED) { nop() }
                val restarted = assertIs<PollerState.Running>(IoUringManager.pollerState)
                assertNotSame(withdrawn, restarted, "the op after the withdrawal must start a new life")

                IoUringManager.stop(withdrawn)

                assertSame(restarted, IoUringManager.pollerState, "a stop of the withdrawn life ended the restarted one")
                assertEquals(0, withTimeout(WAIT) { restart.await() }, "the restarted loop must run the op on its ring")
            } finally {
                gate.release()
                IoUringManager.queueInit.value = gate.real
            }
            IoUringManager.cleanup()
        }

    /**
     * cleanup() stops a life whose ring setup is failing, and an operation that arrives while cleanup
     * waits for that life starts a new one. The failing life's withdrawal ends only itself: its own
     * caller gets the typed setup failure, the new life keeps running, and its caller gets a result.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test
    fun aCleanupDuringAFailingSetupLeavesARestartInItsWindowRunning() {
        // cleanup() blocks its thread until the life it stops has ended, so it runs on its own thread,
        // in a scope that is not a child of runBlocking, and that thread is closed only on success.
        val bg = CoroutineScope(SupervisorJob())
        val cleanupThread = newSingleThreadContext("lifecycle-cleanup")
        runBlocking {
            IoUringManager.cleanup()
            val gate = GatedRefusal(IoUringManager.queueInit.value)
            IoUringManager.queueInit.value = gate.init
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { nop() } }
                withTimeout(WAIT) { gate.entered.await() }
                val failing = assertIs<PollerState.Running>(IoUringManager.pollerState)

                val cleanedUp = CompletableDeferred<Unit>()
                bg.launch(cleanupThread) {
                    IoUringManager.cleanup()
                    cleanedUp.complete(Unit)
                }
                withTimeout(WAIT) { while (IoUringManager.pollerState === failing) delay(1) }
                assertEquals(PollerState.Idle, IoUringManager.pollerState)

                val restart = async(start = CoroutineStart.UNDISPATCHED) { nop() }
                val restarted = assertIs<PollerState.Running>(IoUringManager.pollerState)
                assertNotSame(failing, restarted, "the op after cleanup's stop must start a new life")

                gate.release()
                val failure = withTimeout(WAIT) { first.await() }.exceptionOrNull()
                assertIs<IoUringFailure>(failure, "an op queued for a life whose ring was refused fails typed")
                withTimeout(WAIT) { cleanedUp.await() }

                assertSame(restarted, IoUringManager.pollerState, "the withdrawal ended the restarted life")
                assertEquals(0, withTimeout(WAIT) { restart.await() }, "the restarted loop must run the op on its ring")
            } finally {
                gate.release()
                IoUringManager.queueInit.value = gate.real
            }
            IoUringManager.cleanup()
        }
        bg.cancel()
        cleanupThread.close()
    }

    private suspend fun nop(): Int = IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) }

    private companion object {
        val WAIT = 10.seconds
    }
}

/**
 * A [QueueInit] that refuses one whole setup ladder with EINVAL, parking the event loop thread inside
 * the first refusal until [release], then hands every later setup to [real].
 */
@OptIn(ExperimentalForeignApi::class)
private class GatedRefusal(
    val real: QueueInit,
) {
    /** Completed once the event loop is inside the refused setup. */
    val entered = CompletableDeferred<Unit>()
    private val released = AtomicInt(0)
    private val refused = AtomicInt(0)

    fun release() {
        released.value = 1
    }

    val init: QueueInit = { ring, params ->
        if (refused.value < IO_URING_SETUP_LADDER.size) {
            entered.complete(Unit)
            while (released.value == 0) usleep(1_000u)
            refused.incrementAndGet()
            -EINVAL
        } else {
            real(ring, params)
        }
    }
}
