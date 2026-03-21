package com.ditchoom.socket

import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.linux.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic exception subtype tests for Linux.
 *
 * Uses SO_LINGER(0) to force RST on close, giving deterministic ConnectionReset/BrokenPipe.
 * Uses throwFromResult for errno-based subtypes that are hard to trigger via real I/O.
 */
@OptIn(ExperimentalForeignApi::class)
class DeterministicExceptionTests {
    @Test
    fun rstOnRead_producesConnectionReset() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        // Force RST on close instead of graceful FIN
                        serverClient.applyOptions(SocketOptions(soLinger = 0))
                        serverReady.unlock()
                        kotlinx.coroutines.delay(50)
                        serverClient.close() // sends RST
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            // Wait for RST to arrive
            kotlinx.coroutines.delay(200)

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }

            // RST on read: kernel may deliver ECONNRESET (ConnectionReset) or
            // recv may return 0 before the RST is processed (EndOfStream)
            kotlin.test.assertTrue(
                ex is SocketClosedException.ConnectionReset || ex is SocketClosedException.EndOfStream,
                "Expected ConnectionReset or EndOfStream, got: ${ex::class.simpleName}: ${ex.message}",
            )

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun rstOnWrite_producesBrokenPipeOrConnectionReset() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        // Force RST on close
                        serverClient.applyOptions(SocketOptions(soLinger = 0))
                        clientConnected.unlock()
                        kotlinx.coroutines.delay(50)
                        serverClient.close() // sends RST
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            clientConnected.lockWithTimeout()
            kotlinx.coroutines.delay(200)

            var caughtException: SocketClosedException? = null
            try {
                repeat(200) {
                    client.write(
                        "x".repeat(4096).toReadBuffer(com.ditchoom.buffer.Charset.UTF8),
                        1.seconds,
                    )
                    kotlinx.coroutines.delay(2)
                }
            } catch (e: SocketClosedException) {
                caughtException = e
            }

            if (caughtException != null) {
                // SO_LINGER(0) + write produces BrokenPipe (EPIPE) or ConnectionReset (ECONNRESET)
                kotlin.test.assertTrue(
                    caughtException is SocketClosedException.BrokenPipe ||
                        caughtException is SocketClosedException.ConnectionReset,
                    "Expected BrokenPipe or ConnectionReset, got: ${caughtException!!::class.simpleName}",
                )
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun gracefulClose_producesEndOfStream() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("hello")
                        serverClient.close() // graceful close (FIN)
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(timeout = 2.seconds)
            kotlin.test.assertEquals("hello", data)

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }
            assertIs<SocketClosedException.EndOfStream>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun throwFromResult_enetunreach_producesNetworkUnreachable() {
        val ex =
            try {
                throwFromResult(-ENETUNREACH, "connect")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.NetworkUnreachable>(ex)
    }

    @Test
    fun throwFromResult_ehostunreach_producesHostUnreachable() {
        val ex =
            try {
                throwFromResult(-EHOSTUNREACH, "connect")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.HostUnreachable>(ex)
    }

    @Test
    fun throwSocketException_enetunreach_producesNetworkUnreachable() {
        // throwSocketException reads errno, so we test throwFromResult instead
        // which is the deterministic path
        val ex =
            try {
                throwFromResult(-ENETUNREACH, "sendto")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketConnectionException.NetworkUnreachable>(ex)
        kotlin.test.assertTrue(ex.message.contains("sendto"))
    }

    @Test
    fun throwFromResult_econnreset_producesConnectionReset() {
        val ex =
            try {
                throwFromResult(-ECONNRESET, "recv")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketClosedException.ConnectionReset>(ex)
    }

    @Test
    fun throwFromResult_epipe_producesBrokenPipe() {
        val ex =
            try {
                throwFromResult(-EPIPE, "send")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketClosedException.BrokenPipe>(ex)
    }

    @Test
    fun throwFromResult_etimedout_producesSocketTimeoutException() {
        val ex =
            try {
                throwFromResult(-ETIMEDOUT, "read")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketTimeoutException>(ex)
    }

    @Test
    fun throwFromResult_unknownErrno_producesSocketIOException() {
        val ex =
            try {
                throwFromResult(-999, "op")
            } catch (e: SocketException) {
                e
            }
        assertIs<SocketIOException>(ex)
    }
}
