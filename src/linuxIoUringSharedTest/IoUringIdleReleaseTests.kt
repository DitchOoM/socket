package com.ditchoom.socket.iouring

import com.ditchoom.socket.iouring.linux.io_uring_prep_nop
import com.ditchoom.socket.iouring.linux.io_uring_prep_poll_add
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.ECANCELED
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.pipe
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The ring is released when the last socket closes, and that release must not end an operation that
 * is still in flight. A connect is not counted as a socket until it completes, so on CI a
 * `bidirectionalDataTransfer` connect failed with `ECANCELED` when the previous test's accepted socket
 * closed late, dropped the count to zero and stopped the loop under it. Both modules' rings run this
 * manager, so both run this test.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringIdleReleaseTests {
    /**
     * A poll on an empty pipe stands in for the uncounted connect: it is in flight and belongs to no
     * socket. Whether the stop finds it still queued or already in the ring, it must keep waiting, and
     * complete when its pipe becomes readable.
     */
    @Test
    fun theLastSocketsCloseLeavesAnOperationStillInFlightRunning() =
        runBlocking {
            IoUringManager.cleanup()
            // Every other test closes what it opens, so this test's close is the last one.
            assertEquals(0, IoUringManager.activeSockets, "a socket another test opened is still counted")
            memScoped {
                val fds = allocArray<IntVar>(2)
                check(pipe(fds) == 0) { "pipe() failed" }
                val readFd = fds[0]
                val writeFd = fds[1]
                try {
                    // UNDISPATCHED: the poll is queued and the poller started before async returns.
                    val poll =
                        async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            IoUringManager.submitAndWait(10.seconds) { sqe, _ ->
                                io_uring_prep_poll_add(sqe, readFd, POLLIN.toUInt())
                            }
                        }

                    // Another socket opens and closes while the poll waits; its close is the last one.
                    IoUringManager.onSocketOpened()
                    withContext(Dispatchers.Default) { IoUringManager.onSocketClosed() }

                    val one = alloc<ByteVar>()
                    one.value = 1
                    check(write(writeFd, one.ptr, 1uL) == 1L) { "write() to the pipe failed" }

                    val result = withTimeout(10.seconds) { poll.await() }
                    assertTrue(
                        result > 0 && (result and POLLIN) != 0,
                        "the poll must complete readable after the last socket's close, but returned $result " +
                            "(-ECANCELED is ${-ECANCELED}): the release stopped the loop under an operation " +
                            "still in flight",
                    )
                } finally {
                    close(readFd)
                    close(writeFd)
                    IoUringManager.cleanup()
                }
            }
        }

    /**
     * A caller that read a life just before it granted an idle release sends its operation after the
     * loop's last drain: it never reached a ring, so it must run on the next life, not fail with
     * `-ECANCELED` as an operation of a stopped life would.
     */
    @Test
    fun anOperationSentAsALifeGrantsAnIdleReleaseRunsOnTheNextLife() =
        runBlocking {
            IoUringManager.cleanup()
            assertEquals(0, IoUringManager.activeSockets, "a socket another test opened is still counted")
            withTimeout(10.seconds) { IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) } }
            val idle = assertIs<PollerState.Running>(IoUringManager.pollerState)
            val late = CompletableDeferred<Int>()
            IoUringManager.idleReleaseGranted.value = { life ->
                life.queue.trySend(
                    SubmissionRequest(IoUringManager.nextUserData(), late, null, SubmissionKind.Awaited) { sqe, _ ->
                        io_uring_prep_nop(sqe)
                    },
                )
            }
            try {
                IoUringManager.releaseIfIdle(idle)
            } finally {
                IoUringManager.idleReleaseGranted.value = { }
            }
            assertEquals(
                0,
                withTimeout(10.seconds) { late.await() },
                "an operation sent into an idle-released life must run on the next life (-ECANCELED is ${-ECANCELED})",
            )
            assertNotSame<PollerState>(idle, IoUringManager.pollerState, "the operation must have started a new life")
            IoUringManager.cleanup()
        }

    /**
     * A caller that sent its operation into a life as that life granted an idle release, and then gave
     * up on it, cancels it by user_data. The operation runs on the next life, so the cancel must reach the
     * ring after it: dropped because no life was running at that instant, or sent to the next life ahead
     * of the operation it names, it finds nothing, and the operation — here a poll on a pipe nobody writes
     * — holds the caller's buffers in the kernel after the caller has gone.
     */
    @Test
    fun aCancelSentWhileALifeGrantsAnIdleReleaseFollowsItsOperationToTheNextLife() =
        cancelFollowsItsForwardedOperation(nextLifeStartedFirst = false)

    /** As above, with another submitter starting the next life before the cancel is sent. */
    @Test
    fun aCancelSentAfterTheNextLifeStartedStillFollowsItsForwardedOperation() =
        cancelFollowsItsForwardedOperation(nextLifeStartedFirst = true)

    private fun cancelFollowsItsForwardedOperation(nextLifeStartedFirst: Boolean) =
        runBlocking {
            IoUringManager.cleanup()
            assertEquals(0, IoUringManager.activeSockets, "a socket another test opened is still counted")
            memScoped {
                val fds = allocArray<IntVar>(2)
                check(pipe(fds) == 0) { "pipe() failed" }
                val readFd = fds[0]
                val writeFd = fds[1]
                try {
                    withTimeout(10.seconds) { IoUringManager.submitAndWait { sqe, _ -> io_uring_prep_nop(sqe) } }
                    val idle = assertIs<PollerState.Running>(IoUringManager.pollerState)
                    val parked = CompletableDeferred<Int>()
                    val parkedUserData = IoUringManager.nextUserData()
                    IoUringManager.idleReleaseGranted.value = { life ->
                        life.queue.trySend(
                            SubmissionRequest(parkedUserData, parked, null, SubmissionKind.Awaited) { sqe, _ ->
                                io_uring_prep_poll_add(sqe, readFd, POLLIN.toUInt())
                            },
                        )
                        // A submitter that reads the state now: it starts the next life if none is running.
                        if (nextLifeStartedFirst) IoUringManager.registerOperation()
                        IoUringManager.cancelOperation(parkedUserData)
                    }
                    try {
                        IoUringManager.releaseIfIdle(idle)
                    } finally {
                        IoUringManager.idleReleaseGranted.value = { }
                    }
                    val result = withTimeout(10.seconds) { parked.await() }
                    assertEquals(
                        -ECANCELED,
                        result,
                        "the cancelled poll must end -ECANCELED (${-ECANCELED}), but returned $result",
                    )
                } catch (e: TimeoutCancellationException) {
                    throw AssertionError(
                        "the poll was still parked 10 s after its cancel: the cancel was dropped, or reached " +
                            "the next life ahead of the operation it names",
                        e,
                    )
                } finally {
                    close(readFd)
                    close(writeFd)
                    IoUringManager.cleanup()
                }
            }
        }
}
