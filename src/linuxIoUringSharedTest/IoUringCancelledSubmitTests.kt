package com.ditchoom.socket.iouring

import com.ditchoom.socket.iouring.linux.io_uring_prep_nop
import com.ditchoom.socket.iouring.linux.io_uring_prep_poll_add
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.pipe
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A cancelled submit returns only once its operation has ended. Its caller's next step is typically to close
 * the descriptor the operation names; an operation the poller has not prepared yet would then name whatever
 * the process opens next under that number — a connect attempt's loser, a quiche or test proxy channel, any
 * raw-descriptor caller. The poller is held here inside another operation's prepare for longer than any
 * bounded post-cancel wait, so the cancelled operation is still unprepared when its caller gives up on it.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringCancelledSubmitTests {
    @Test
    fun aCancelledSubmitReturnsOnlyOnceItsOperationHasEnded() =
        runBlocking {
            memScoped {
                val fds = allocArray<IntVar>(2)
                check(pipe(fds) == 0) { "pipe failed" }
                val readEnd = fds[0]
                val writeEnd = fds[1]
                try {
                    val holding = CompletableDeferred<Unit>()
                    val hold =
                        async(Dispatchers.Default) {
                            IoUringManager.submitAndWait(HOLD_TIMEOUT) { sqe, _ ->
                                holding.complete(Unit)
                                usleep(HOLD_MICROS.convert())
                                io_uring_prep_nop(sqe)
                            }
                        }
                    withTimeout(HOLD_TIMEOUT) { holding.await() }
                    val prepared = AtomicInt(0)
                    // UNDISPATCHED: the request is enqueued before the cancel below, so the poller meets it
                    // only once the hold is over.
                    val parked =
                        async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            IoUringManager.submitAndWait { sqe, _ ->
                                prepared.value = 1
                                io_uring_prep_poll_add(sqe, readEnd, POLLIN.convert())
                            }
                        }
                    parked.cancel()
                    parked.join()
                    assertEquals(
                        1,
                        prepared.value,
                        "a cancelled submit returned while the poller had not yet prepared its operation: the " +
                            "caller may now close the descriptor that operation will be submitted against",
                    )
                    hold.await()
                } finally {
                    close(readEnd)
                    close(writeEnd)
                }
            }
        }

    private companion object {
        /** Five times the bounded wait a cancelled submit used to allow itself. */
        const val HOLD_MICROS = 500_000
        val HOLD_TIMEOUT = 10.seconds
    }
}
