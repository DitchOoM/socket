package com.ditchoom.socket.udp

import com.ditchoom.socket.udp.linux.io_uring_prep_poll_add
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The ring is released when the last socket closes, and that release must not end an operation that
 * is still in flight — the same contract as `:socket`'s copy of this manager, whose CI failure (a TCP
 * connect ending in `ECANCELED`) found it. Here it covers a socket bound while another's close is
 * releasing the ring.
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
            // Sockets other tests in this binary left open would keep this test's close from being the
            // last one, so their count is set aside for the test and restored after it.
            val others = IoUringManager.activeSockets
            repeat(others) { IoUringManager.onSocketClosed() }
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
                    repeat(others) { IoUringManager.onSocketOpened() }
                }
            }
        }
}
