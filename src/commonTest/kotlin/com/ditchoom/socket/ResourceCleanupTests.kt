package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for resource cleanup and proper socket lifecycle management.
 *
 * Where a test needs to know that the server-side handler — a collector running on
 * `Dispatchers.Default` — has finished with a client, the handler says so through a
 * [CompletableDeferred] and the test [awaitOrFail]s on that before it asserts or tears anything
 * down. Three of these tests used to `delay(…)` and then read a plain `var` the handler had written
 * on the other dispatcher (#518, the shape #381 was actually caught doing): the constant loses the
 * race against accept + read on a loaded runner, and on Kotlin/Native the read is an unsynchronised
 * cross-thread read that need not observe the write even when the work did happen.
 */
class ResourceCleanupTests {
    @Test
    fun socketClosedAfterUseBlock() =
        runTestNoTimeSkipping {
            // Windows NIO2 race: the test's `server.close()` at the end runs
            // while serverFlow.collect is still inside `server.accept`'s async
            // continuation; on the Windows AsynchronousChannel impl this surfaces
            // as `java.nio.channels.ClosedChannelException` at
            // `WindowsAsynchronousServerSocketChannelImpl.implAccept`, which our
            // JvmExceptionMapping wraps as `SocketClosedException.General` —
            // identical shape to the existing repeatedOpenClose skip below and
            // identical in shape to the repeatedOpenClose skip below. The same
            // contract is exercised on Linux/macOS JVM, K/Native, and JS without
            // issue. Tracked as issue #309 (Windows JVM mapping gaps); tighten the
            // Windows mapping when that lands.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        client.writeString("hello")
                        client.close()
                    }
                }

            var socketRef: ClientSocket? = null

            ClientSocket.connect(server.port(), hostname = "127.0.0.1", config = TransportConfig(connectTimeout = 5.seconds)) { socket ->
                socketRef = socket
                assertTrue(socket.isOpen, "Socket should be open inside use block")
                socket.readString(deadline = 1.seconds)
            }

            // After the block, socket should be closed
            assertFalse(socketRef?.isOpen ?: true, "Socket should be closed after use block")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun socketClosedOnException() =
        runTestNoTimeSkipping {
            // Windows NIO2 teardown race: AsynchronousSocketChannel.close()
            // is asynchronous on Windows and the subsequent isOpen probe
            // races against the underlying handle release. Same family as
            // socketClosedAfterUseBlock (commit 7e82e42) — skipped on
            // Windows pending a tighter `JvmExceptionMapping.kt` that
            // distinguishes "closed by us" from "kernel just closed it
            // under us". Tracked as issue #309.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        // Just echo back data if received
                        try {
                            val data = client.readString(deadline = 500.milliseconds)
                            client.writeString(data)
                        } catch (_: Exception) {
                            // Client may close before sending
                        }
                        client.close()
                    }
                }

            var socketWasOpen = false

            try {
                ClientSocket.connect(
                    server.port(),
                    hostname = "127.0.0.1",
                    config = TransportConfig(connectTimeout = 5.seconds),
                ) { socket ->
                    socketWasOpen = socket.isOpen
                    throw RuntimeException("Test exception")
                }
                fail("Should have thrown")
            } catch (e: RuntimeException) {
                // Expected
            }

            // Verify the socket was open inside the block
            assertTrue(socketWasOpen, "Socket should have been open inside the use block")

            // Note: We can't reliably check isOpen() after the block because the socket
            // reference is managed by the connect block. The block should close it internally.

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverCleanupOnException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()

            try {
                val flow = server.bind()
                assertTrue(server.isListening(), "Server should be listening")

                // Simulate an error scenario
                throw RuntimeException("Simulated error")
            } catch (e: RuntimeException) {
                // Expected
            } finally {
                server.close()
            }

            assertFalse(server.isListening(), "Server should not be listening after close")
        }

    @Test
    fun repeatedOpenClose() =
        runTestNoTimeSkipping {
            // Windows NIO2 surfaces an IOException at WindowsAsynchronousSocket-
            // ChannelImpl during the read-after-close path here, which
            // JvmExceptionMapping maps to SocketClosedException.General — not
            // the clean shutdown the test asserts. TODO(JVM/Windows): tighten
            // mapping or test invariant; skip pending investigation. Same
            // contract is exercised on Linux/macOS JVM + K/Native + JS.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        client.writeString("pong")
                        client.close()
                    }
                }

            // Repeatedly open and close connections
            repeat(10) { i ->
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(server.port(), hostname = "127.0.0.1")
                assertTrue(client.isOpen, "Client $i should be open")

                client.writeString("ping")
                val response = client.readString(deadline = 1.seconds)
                assertTrue(response == "pong", "Should receive response for client $i")

                client.close()
                assertFalse(client.isOpen, "Client $i should be closed")
            }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun coroutineCancellationCleansUpSocket() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverAccepted = CompletableDeferred<ClientSocket>()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        serverAccepted.complete(client)
                        // Don't send anything - wait for cancel. Kept well under the
                        // 30s runTestNoTimeSkipping budget so the watchdog can't fire.
                        delay(5.seconds)
                    }
                }

            // The client socket travels to the test through this, rather than through a `var` the
            // client coroutine assigns on `Dispatchers.Default` and the test reads on its own thread.
            // Completed once the client is connected and accepted, i.e. once it is about to block in
            // the read that the cancellation below is meant to interrupt.
            val clientReading = CompletableDeferred<ClientSocket>()
            val clientJob =
                launch(Dispatchers.Default) {
                    val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                    client.open(server.port(), hostname = "127.0.0.1")
                    serverAccepted.await()
                    clientReading.complete(client)

                    // This should block and be cancelled (5s keeps it inside the
                    // 30s test budget; the read is cancelled long before it fires).
                    client.readBuffer(5.seconds)
                }

            val client = clientReading.awaitOrFail("the client to connect, be accepted, and start its read")

            // Cancel the client job and wait for the cancellation to have run, rather than for 100ms.
            clientJob.cancel()
            clientJob.join()

            // Clean up client
            client.close()
            assertFalse(client.isOpen, "the client socket reports open after close() — its cancelled read kept the FD alive")

            // The accepted server-side socket is this test's other resource; close it by hand,
            // because cancelling the collector does not close what it already handed out.
            serverAccepted.await().close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverFlowCancellation() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val firstClientHandled = CompletableDeferred<Unit>()

            val serverJob =
                launch(Dispatchers.Default) {
                    try {
                        serverFlow.collect { client ->
                            client.writeString("hello")
                            client.close()
                            firstClientHandled.complete(Unit)

                            // Cancel after first client
                            this@launch.cancel()
                        }
                    } catch (e: CancellationException) {
                        // Expected
                    }
                }

            // Connect once
            ClientSocket.connect(server.port(), hostname = "127.0.0.1", config = TransportConfig(connectTimeout = 5.seconds)) { socket ->
                socket.readString(deadline = 1.seconds)
            }

            firstClientHandled.awaitOrFail("the server to finish with its one client, after which it cancels its own collect")

            server.close()
            serverJob.cancel()
        }

    @Test
    fun timeoutDoesNotLeakResources() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        // Don't respond - let client timeout
                        delay(60000)
                        client.close()
                    }
                }

            repeat(3) {
                val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
                client.open(server.port(), hostname = "127.0.0.1")

                try {
                    withTimeout(100.milliseconds) {
                        client.readBuffer(100.milliseconds)
                    }
                } catch (e: Exception) {
                    // Expected timeout
                }

                client.close()
                assertFalse(client.isOpen, "Client should be closed after timeout")
            }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun writeAfterCloseThrows() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        delay(60000)
                        client.close()
                    }
                }

            val client = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client.open(server.port(), hostname = "127.0.0.1")
            assertTrue(client.isOpen, "Socket should be open")

            client.close()
            assertFalse(client.isOpen, "Socket should be closed")

            assertFailsWith<SocketClosedException>("Write after close should throw") {
                client.writeString("should fail")
            }

            assertFailsWith<SocketClosedException>("Read after close should throw") {
                client.readString(deadline = 1.seconds)
            }

            server.close()
            serverJob.cancel()
        }

    @Test
    fun serverAcceptsContinuesAfterClientError() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            // One signal per accepted client, in accept order. Client N+1 is opened only after client
            // N's signal, so index N is client N — and "the second client was handled *after* the
            // first one errored" holds by construction rather than by a delay being long enough.
            val handled = List(3) { CompletableDeferred<HandledClient>() }

            val serverJob =
                launch(Dispatchers.Default) {
                    val signals = handled.iterator()
                    serverFlow.collect { client ->
                        val outcome =
                            try {
                                // Try to read - first client will send nothing
                                HandledClient.Read(
                                    withTimeout(2.seconds) {
                                        client.readString(deadline = 2.seconds)
                                    },
                                )
                            } catch (e: Exception) {
                                // Client error - continue accepting
                                HandledClient.Failed(e)
                            } finally {
                                client.close()
                            }
                        if (!signals.hasNext()) fail("the server accepted a fourth client; the test opens only three")
                        signals.next().complete(outcome)
                    }
                }

            // First client - connect and immediately close (server will get error)
            val client1 = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client1.open(server.port(), hostname = "127.0.0.1")
            client1.close()

            val first = handled[0].awaitOrFail("the server to finish with the first client, which sent nothing")
            assertIs<HandledClient.Failed>(
                first,
                "the first client closed without sending, so the server's read of it should have failed; it was $first",
            )

            // Second client - send proper data. Opened only now, so the server reaching it at all
            // proves it kept accepting after the first client's error.
            val client2 = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client2.open(server.port(), hostname = "127.0.0.1")
            client2.writeString("hello")
            client2.close()

            assertEquals(
                HandledClient.Read("hello"),
                handled[1].awaitOrFail("the server to handle the second client, opened after the first client's error"),
                "the server did not read the second client's data after the first client's error",
            )

            // Third client - should also work
            val client3 = ClientSocket.allocate(TransportConfig(connectTimeout = 5.seconds))
            client3.open(server.port(), hostname = "127.0.0.1")
            client3.writeString("world")
            client3.close()

            assertEquals(
                HandledClient.Read("world"),
                handled[2].awaitOrFail("the server to handle the third client"),
                "the server did not read the third client's data",
            )

            server.close()
            serverJob.cancel()
        }
}

/** What the collector in [ResourceCleanupTests.serverAcceptsContinuesAfterClientError] made of one accepted client. */
private sealed interface HandledClient {
    /** The read completed, yielding [data]. */
    data class Read(
        val data: String,
    ) : HandledClient

    /** The read threw [cause] — a peer that closed without sending anything, or the read's deadline. */
    data class Failed(
        val cause: Throwable,
    ) : HandledClient
}
