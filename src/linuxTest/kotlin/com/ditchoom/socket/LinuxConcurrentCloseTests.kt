package com.ditchoom.socket

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic regression for the io_uring close-during-pending-I/O race — the bug behind the
 * intermittent `ExceptionConformanceTests.pendingReadDuringPeerReset` failure on Linux ARM64.
 *
 * That harness test depends on toxiproxy/kernel timing for *which* errno a peer-reset surfaces, so
 * it can't deterministically isolate the defect. This one does, with no harness and no network
 * timing: it closes the socket out from under a parked `read()` on plain loopback. The in-flight
 * io_uring `recv` then completes with `-ECANCELED`/`-EBADF` (fd closed), or — if the close wins the
 * race — the next op trips the `sockfd < 0` guard. **Every branch must surface a
 * [SocketClosedException]**; before the EBADF/ECANCELED mapping fix, the cancelled op produced a
 * generic [SocketIOException] instead. Paired with the pure-mapping [LinuxExceptionMappingTests]
 * (`ebadf_producesSocketClosed`, `ecanceled_producesSocketClosed`), this locks the contract end to
 * end with zero reliance on scheduling.
 */
class LinuxConcurrentCloseTests {
    @Test
    fun concurrentCloseDuringPendingRead_surfacesAsSocketClosed() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val flow = server.bind()

            // Accept the connection and hold it open without ever writing, so the client's read
            // genuinely parks on the kernel recv instead of returning data.
            val serverJob =
                launch(Dispatchers.Default) {
                    val accepted = flow.first()
                    // Park here; the test drives the lifecycle from the client side.
                    try {
                        accepted.read(10.seconds)
                    } catch (_: Throwable) {
                        // Connection torn down by the test — expected.
                    } finally {
                        accepted.close()
                    }
                }

            val client = ClientSocket.connect(server.port(), hostname = "127.0.0.1", timeout = 5.seconds)
            try {
                // Park a read in a child coroutine. UNDISPATCHED runs the body up to its first real
                // suspension — the io_uring recv submit — so the kernel-side read is registered
                // before async() returns.
                val parkedRead =
                    async<Throwable?>(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            client.read(10.seconds)
                            null
                        } catch (t: Throwable) {
                            t
                        }
                    }
                yield() // let the recv SQE actually reach the ring before we close the fd

                // Close the fd out from under the parked recv — the deterministic trigger.
                client.close()

                val thrown = parkedRead.await()
                assertNotNull(thrown, "expected the parked read to throw once its socket was closed")
                assertIs<SocketClosedException>(
                    thrown,
                    "close-during-pending-read must surface as SocketClosedException, got " +
                        "${thrown::class.simpleName}(${thrown.message})",
                )
            } finally {
                client.close()
                serverJob.cancel()
                server.close()
            }
        }
}
