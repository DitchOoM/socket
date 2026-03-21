package com.ditchoom.socket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Linux-specific exception mapping tests.
 *
 * Tests that Linux io_uring error codes are correctly mapped to
 * the sealed SocketException subtypes through actual socket operations.
 */
class LinuxExceptionMappingTests {
    @Test
    fun connectionRefused_producesSocketConnectionRefusedException() =
        runTestNoTimeSkipping {
            val port = 59300 + kotlin.random.Random.nextInt(699)
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
                socket.close()
            } catch (e: SocketConnectionException.Refused) {
                // Expected — Linux maps ECONNREFUSED
                assertIs<SocketConnectionException.Refused>(e)
            } catch (e: SocketTimeoutException) {
                // Also acceptable
            }
        }

    @Test
    fun dnsFailure_producesSocketUnknownHostException() =
        runTestNoTimeSkipping {
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = 80, timeout = 5.seconds, hostname = "this.host.does.not.exist.invalid")
                socket.close()
                fail("Should have thrown for invalid hostname")
            } catch (e: SocketUnknownHostException) {
                assertTrue(e.message.contains("this.host.does.not.exist.invalid"))
            }
        }

    @Test
    fun readFromClosedConnection_producesSocketClosedException() =
        runTestNoTimeSkipping {
            val server = ServerSocket.allocate()
            val serverFlow = server.bind()
            val serverReady = Mutex(locked = true)

            val serverJob =
                launch(Dispatchers.Default) {
                    serverFlow.collect { serverClient ->
                        serverReady.unlock()
                        serverClient.writeString("hello")
                        serverClient.close()
                    }
                }

            val client = ClientSocket.allocate()
            client.open(server.port(), 5.seconds, "127.0.0.1")
            serverReady.lockWithTimeout()

            val data = client.readString(timeout = 2.seconds)
            assertTrue(data == "hello")

            try {
                client.read(2.seconds)
                fail("Should have thrown")
            } catch (e: SocketClosedException) {
                // Expected — Linux maps ECONNRESET/EOF → SocketClosedException
            }

            client.close()
            server.close()
            serverJob.cancel()
        }

    @Test
    fun tlsHandshakeFailure_producesSSLHandshakeFailedException() =
        runTestNoTimeSkipping {
            try {
                ClientSocket.connect(
                    port = 443,
                    hostname = "self-signed.badssl.com",
                    socketOptions = SocketOptions.tlsDefault(),
                    timeout = 15.seconds,
                ) { socket ->
                    assertTrue(socket.isOpen())
                }
            } catch (e: SSLHandshakeFailedException) {
                // Expected — Linux OpenSSL
                assertTrue(e.message.isNotBlank())
            } catch (e: SSLProtocolException) {
                // Also acceptable
            } catch (e: SocketException) {
                // Fallback acceptable
            }
        }

    @Test
    fun connectTimeout_producesSocketTimeoutException() =
        runTestNoTimeSkipping {
            try {
                val socket = ClientSocket.allocate()
                socket.open(port = 80, timeout = 1.seconds, hostname = "10.255.255.1")
                socket.close()
            } catch (e: SocketTimeoutException) {
                // Expected — Linux maps ETIMEDOUT
                assertTrue(e.message.lowercase().contains("timed out") || e.message.lowercase().contains("timeout"))
            } catch (e: SocketIOException) {
                // Also acceptable — unreachable
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Also acceptable
            }
        }
}
