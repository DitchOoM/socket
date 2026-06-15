package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic tests for issue #83: [LinuxClientSocket.close] must promptly cancel a
 * concurrently-parked io_uring recv so the read surfaces [SocketClosedException] instead
 * of running out its full timeout (the Linux ARM64 prompt-cancel gap; JVM/NIO2 already
 * raises AsynchronousCloseException immediately).
 *
 * Determinism: the test waits until the recv is *observably* parked
 * ([LinuxClientSocket.parkedReadUserDataForTest] != 0) before closing, and asserts on a
 * cancel-submission counter — never on wall-clock timing. The 5s [withTimeout] is only a
 * watchdog against a hang. This is the deliberate replacement for the timing-based
 * LinuxConcurrentCloseTests removed in #82.
 */
class LinuxCloseCancelTests {
    @Test
    fun closeSubmitsCancelForParkedReadAndCompletesPromptly() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val acceptedClientFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            // Server accepts then sits idle (never sends), so the client read parks.
            val serverJob =
                launch(Dispatchers.Default) {
                    acceptedClientFlow.collect { serverToClient ->
                        clientConnected.unlock()
                        delay(30.seconds)
                        serverToClient.close()
                    }
                }

            val client = ClientSocket.allocate() as LinuxClientSocket
            client.open(server.port())
            clientConnected.lockWithTimeout()

            val readResult = CompletableDeferred<Throwable?>()
            launch(Dispatchers.Default) {
                try {
                    client.readBuffer(30.seconds)
                    readResult.complete(null) // unexpected: read returned data
                } catch (e: Throwable) {
                    readResult.complete(e)
                }
            }

            // Deterministic gate: wait until the recv is actually parked in io_uring,
            // not a fixed sleep. Only then is close() guaranteed to race a live op, and
            // (because the parked flag is set on the event loop thread after the recv is
            // registered) the cancel can never be enqueued ahead of the recv.
            withTimeout(5.seconds) {
                while (client.parkedReadUserDataForTest() == 0L) {
                    delay(5)
                }
            }

            val cancelsBefore = IoUringManager.cancelSubmitCount.value
            client.close()

            // Assertion #1 (the deterministic one): close() submitted an io_uring cancel
            // for the parked recv rather than relying on fd-close to do it.
            assertTrue(
                IoUringManager.cancelSubmitCount.value > cancelsBefore,
                "close() should submit an io_uring cancel for the parked read",
            )

            // Assertion #2: the parked read completes (watchdog, not the timing assertion)
            // with SocketClosedException — NOT SocketTimeoutException, the #83 symptom.
            val thrown = withTimeout(5.seconds) { readResult.await() }
            assertTrue(
                thrown is SocketClosedException,
                "parked read should fail with SocketClosedException after close(), was: $thrown",
            )

            server.close()
            serverJob.cancel()
        }
}
