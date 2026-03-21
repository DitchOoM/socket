package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [wrapJvmException] — the centralized JVM → SocketException mapping.
 *
 * Tests every branch with synthetic JVM exceptions, no network I/O needed.
 */
class WrapJvmExceptionTests {
    // ==================== ConnectException branches ====================

    @Test
    fun connectException_refused_mapsToSocketConnectionRefusedException() {
        val original = ConnectException("Connection refused")
        val result = wrapJvmException(original, "localhost", 8080)
        assertIs<SocketConnectionException.Refused>(result)
        assertEquals("localhost", result.host)
        assertEquals(8080, result.port)
        assertSame(original, result.cause)
        assertNotNull(result.platformError)
    }

    @Test
    fun connectException_timedOut_mapsToSocketTimeoutException() {
        val original = ConnectException("Connection timed out")
        val result = wrapJvmException(original, "10.0.0.1", 443)
        assertIs<SocketTimeoutException>(result)
        assertEquals("10.0.0.1", result.host)
        assertEquals(443, result.port)
        assertSame(original, result.cause)
    }

    @Test
    fun connectException_networkUnreachable_mapsToNetworkUnreachable() {
        val original = ConnectException("Network is unreachable")
        val result = wrapJvmException(original)
        assertIs<SocketConnectionException.NetworkUnreachable>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun connectException_noRouteToHost_mapsToHostUnreachable() {
        val original = ConnectException("No route to host")
        val result = wrapJvmException(original)
        assertIs<SocketConnectionException.HostUnreachable>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun connectException_generic_mapsToSocketIOException() {
        val original = ConnectException("Some other connect error")
        val result = wrapJvmException(original)
        assertIs<SocketIOException>(result)
        assertSame(original, result.cause)
    }

    // ==================== UnknownHostException ====================

    @Test
    fun unknownHostException_mapsToSocketUnknownHostException() {
        val original = UnknownHostException("bad.host")
        val result = wrapJvmException(original, "bad.host")
        assertIs<SocketUnknownHostException>(result)
        assertEquals("bad.host", result.hostname)
        assertSame(original, result.cause)
    }

    // ==================== Channel close exceptions ====================

    @Test
    fun asynchronousCloseException_mapsToSocketClosedGeneral() {
        val original = AsynchronousCloseException()
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.General>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun closedChannelException_mapsToSocketClosedGeneral() {
        val original = ClosedChannelException()
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.General>(result)
        assertSame(original, result.cause)
    }

    // ==================== SSL exceptions ====================

    @Test
    fun sslHandshakeException_mapsToSSLHandshakeFailedException() {
        val original = javax.net.ssl.SSLHandshakeException("certificate expired")
        val result = wrapJvmException(original)
        assertIs<SSLHandshakeFailedException>(result)
        assertTrue(result.message.contains("certificate expired"))
        assertSame(original, result.cause)
    }

    @Test
    fun sslException_mapsToSSLProtocolException() {
        val original = javax.net.ssl.SSLException("protocol error")
        val result = wrapJvmException(original)
        assertIs<SSLProtocolException>(result)
        assertTrue(result.message.contains("protocol error"))
        assertSame(original, result.cause)
    }

    // ==================== IOException branches ====================

    @Test
    fun ioException_brokenPipe_mapsToSocketClosedBrokenPipe() {
        val original = java.io.IOException("Broken pipe")
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.BrokenPipe>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun ioException_connectionReset_mapsToSocketClosedConnectionReset() {
        val original = java.io.IOException("Connection reset")
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.ConnectionReset>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun ioException_closed_mapsToSocketClosedGeneral() {
        val original = java.io.IOException("Stream closed")
        val result = wrapJvmException(original)
        assertIs<SocketClosedException.General>(result)
        assertSame(original, result.cause)
    }

    @Test
    fun ioException_generic_mapsToSocketIOException() {
        val original = java.io.IOException("Disk error or something")
        val result = wrapJvmException(original)
        assertIs<SocketIOException>(result)
        assertSame(original, result.cause)
    }

    // ==================== SocketTimeoutException (java.net) ====================

    @Test
    fun javaSocketTimeoutException_mapsToSocketTimeoutException() {
        val original = java.net.SocketTimeoutException("Read timed out")
        val result = wrapJvmException(original, "example.com", 80)
        assertIs<SocketTimeoutException>(result)
        assertEquals("example.com", result.host)
        assertEquals(80, result.port)
        assertSame(original, result.cause)
    }

    // ==================== Passthrough ====================

    @Test
    fun socketException_passesThrough() {
        val original = SocketIOException("already wrapped")
        val result = wrapJvmException(original)
        assertSame(original, result, "Already-wrapped SocketException should pass through")
    }

    @Test
    fun socketClosedException_passesThrough() {
        val original = SocketClosedException.General("already closed")
        val result = wrapJvmException(original)
        assertSame(original, result)
    }

    // ==================== Fallback ====================

    @Test
    fun runtimeException_mapsToSocketIOException() {
        val original = RuntimeException("unexpected")
        val result = wrapJvmException(original)
        assertIs<SocketIOException>(result)
        assertSame(original, result.cause)
    }

    // ==================== DNS resolution (buildInetAddress) ====================

    @Test
    fun buildInetAddress_unknownHost_wrapsToSocketUnknownHostException() =
        runTestNoTimeSkipping {
            try {
                com.ditchoom.socket.nio.util.buildInetAddress(80, "this.host.does.not.exist.invalid")
                fail("Should have thrown SocketUnknownHostException")
            } catch (e: SocketUnknownHostException) {
                assertTrue(e.message.contains("this.host.does.not.exist.invalid"))
                assertNotNull(e.cause)
            }
        }

    // ==================== asyncIOIntHandler wrapping ====================

    @Test
    fun asyncHandler_asynchronousCloseException_wrapsToSocketClosedException() =
        runTestNoTimeSkipping {
            val original = AsynchronousCloseException()
            val result = captureHandlerException(original)
            assertIs<SocketClosedException>(result)
            assertSame(original, result.cause)
        }

    @Test
    fun asyncHandler_connectException_wrapsToSocketConnectionRefusedException() =
        runTestNoTimeSkipping {
            val original = ConnectException("Connection refused")
            val result = captureHandlerException(original)
            assertIs<SocketConnectionException.Refused>(result)
        }

    // ==================== Integration: read from closed peer ====================

    @Test
    fun readFromClosedSocket_producesSocketClosedException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val clientConnected = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { client ->
                        clientConnected.unlock()
                        client.writeString("hello")
                        client.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            clientConnected.lockWithTimeout()

            val data = client.readString(timeout = 1.seconds)
            assertEquals("hello", data)

            try {
                client.read(1.seconds)
                fail("Should have thrown")
            } catch (e: SocketClosedException) {
                // Expected
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    // ==================== Helper ====================

    private suspend fun captureHandlerException(exception: Throwable): Throwable =
        try {
            suspendCancellableCoroutine<Int> { cont ->
                val handler = com.ditchoom.socket.nio2.util.asyncIOIntHandler()
                handler.failed(exception, cont)
            }
            error("Handler should have thrown")
        } catch (e: Throwable) {
            e
        }
}
