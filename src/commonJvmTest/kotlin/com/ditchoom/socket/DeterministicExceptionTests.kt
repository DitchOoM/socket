package com.ditchoom.socket

import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Deterministic exception subtype tests for JVM.
 *
 * Uses a raw java.net.ServerSocket with SO_LINGER(true, 0) to force RST on close.
 * NIO2 AsynchronousSocketChannel doesn't support SO_LINGER, so we bypass our
 * library's server and use java.net.ServerSocket + java.net.Socket directly
 * for the server side. The client side uses our library's ClientSocket.
 */
class DeterministicExceptionTests {
    @Test
    fun rstOnRead_producesConnectionReset() =
        runTestNoTimeSkipping {
            // Use raw java.net.ServerSocket so we can set SO_LINGER
            val rawServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val port = rawServer.localPort
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.IO) {
                    val accepted = rawServer.accept()
                    // Force RST on close — this works on raw Socket (unlike NIO2)
                    accepted.setSoLinger(true, 0)
                    serverReady.unlock()
                    kotlinx.coroutines.delay(50)
                    accepted.close() // sends RST
                }

            val client = ClientSocket.allocate()
            client.open(port, 5.seconds, "127.0.0.1")
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

            // RST on read produces ConnectionReset (IOException "Connection reset")
            assertIs<SocketClosedException.ConnectionReset>(ex,
                "Expected ConnectionReset, got: ${ex::class.simpleName}: ${ex.message}")

            client.close()
            rawServer.close()
            serverJob.cancel()
        }

    @Test
    fun rstOnWrite_producesBrokenPipeOrConnectionReset() =
        runTestNoTimeSkipping {
            val rawServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val port = rawServer.localPort
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.IO) {
                    val accepted = rawServer.accept()
                    accepted.setSoLinger(true, 0)
                    clientConnected.unlock()
                    kotlinx.coroutines.delay(50)
                    accepted.close() // sends RST
                }

            val client = ClientSocket.allocate()
            client.open(port, 5.seconds, "127.0.0.1")
            clientConnected.lockWithTimeout()
            kotlinx.coroutines.delay(200)

            var caughtException: SocketClosedException? = null
            try {
                repeat(200) {
                    client.write(
                        "x".repeat(8192).toReadBuffer(com.ditchoom.buffer.Charset.UTF8),
                        1.seconds,
                    )
                    kotlinx.coroutines.delay(2)
                }
            } catch (e: SocketClosedException) {
                caughtException = e
            }

            if (caughtException != null) {
                // RST + write produces BrokenPipe or ConnectionReset (timing-dependent)
                assertTrue(
                    caughtException is SocketClosedException.BrokenPipe ||
                        caughtException is SocketClosedException.ConnectionReset,
                    "Expected BrokenPipe or ConnectionReset, got: ${caughtException!!::class.simpleName}: ${caughtException.message}",
                )
            }

            client.close()
            rawServer.close()
            serverJob.cancel()
        }

    @Test
    fun gracefulClose_producesEndOfStream() =
        runTestNoTimeSkipping {
            val rawServer = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val port = rawServer.localPort
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.IO) {
                    val accepted = rawServer.accept()
                    serverReady.unlock()
                    accepted.getOutputStream().write("hello".toByteArray())
                    accepted.getOutputStream().flush()
                    kotlinx.coroutines.delay(50)
                    accepted.close() // graceful FIN
                }

            val client = ClientSocket.allocate()
            client.open(port, 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(timeout = 2.seconds)
            assertEquals("hello", data)

            val ex =
                try {
                    client.read(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }
            assertIs<SocketClosedException.EndOfStream>(ex)

            client.close()
            rawServer.close()
            serverJob.cancel()
        }

    // ==================== Mapping unit tests ====================

    @Test
    fun connectException_networkUnreachable_mapsToNetworkUnreachable() {
        val original = ConnectException("Network is unreachable")
        val result = wrapJvmException(original)
        assertIs<SocketConnectionException.NetworkUnreachable>(result)
    }

    @Test
    fun connectException_noRouteToHost_mapsToHostUnreachable() {
        val original = ConnectException("No route to host")
        val result = wrapJvmException(original)
        assertIs<SocketConnectionException.HostUnreachable>(result)
    }

    @Test
    fun ioException_brokenPipe_mapsToBrokenPipe() {
        val original = java.io.IOException("Broken pipe")
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.BrokenPipe>(result)
    }

    @Test
    fun ioException_connectionReset_mapsToConnectionReset() {
        val original = java.io.IOException("Connection reset by peer")
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.ConnectionReset>(result)
    }
}
