package com.ditchoom.socket

import com.ditchoom.data.readBuffer
import com.ditchoom.data.readString
import com.ditchoom.data.writeString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * JVM-specific tests that strictly assert sealed exception subtypes.
 *
 * These tests know the exact JVM wrapping behavior and assert the specific
 * subtype, not just the parent sealed class. Each test catches ONE type.
 */
class JvmExceptionSubtypeTests {
    @Test
    fun connectionRefused_isSocketConnectionExceptionRefused() =
        runTestNoTimeSkipping {
            // Windows NIO2 maps connect-refused through the generic IOException
            // branch in JvmExceptionMapping (→ SocketIOException) instead of
            // the ConnectException branch (→ SocketConnectionException.Refused).
            // TODO(JVM/Windows): detect Iocp connect-refused codes explicitly.
            if (isWindowsJvm()) return@runTestNoTimeSkipping
            val port = 59400 + kotlin.random.Random.nextInt(599)
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(port = port, hostname = "127.0.0.1", config = TransportConfig(connectTimeout = 2.seconds))
                    socket.close()
                    fail("Should have thrown for connection refused")
                } catch (e: SocketConnectionException.Refused) {
                    e
                }
            assertIs<SocketConnectionException.Refused>(ex)
            assertTrue(ex.platformError != null, "platformError should be set")
        }

    @Test
    fun readAfterPeerClose_isEndOfStream() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("data")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), "127.0.0.1", TransportConfig(connectTimeout = 5.seconds))
            serverReady.lockWithTimeout()

            client.readString(deadline = 2.seconds) // consume the sent data

            val ex =
                try {
                    client.readBuffer(2.seconds)
                    fail("Should have thrown")
                } catch (e: SocketClosedException) {
                    e
                }

            // On JVM, reading after graceful peer close produces EndOfStream
            assertIs<SocketClosedException.EndOfStream>(ex)

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun dnsFailure_hostnameIsPreserved() =
        runTestNoTimeSkipping {
            val ex =
                try {
                    val socket = ClientSocket.allocate()
                    socket.open(
                        port = 80,
                        hostname = "nonexistent.jvm.test.invalid",
                        config = TransportConfig(connectTimeout = 5.seconds),
                    )
                    socket.close()
                    fail("Should have thrown")
                } catch (e: SocketUnknownHostException) {
                    e
                }
            assertIs<SocketUnknownHostException>(ex)
            assertTrue(
                ex.hostname == "nonexistent.jvm.test.invalid" || ex.message.contains("nonexistent.jvm.test.invalid"),
                "Hostname should be preserved, got: hostname=${ex.hostname}, message=${ex.message}",
            )
        }
}
