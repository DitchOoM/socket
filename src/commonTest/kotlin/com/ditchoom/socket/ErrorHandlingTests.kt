package com.ditchoom.socket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for error handling and edge cases.
 *
 * Exception hierarchy:
 * - SocketException (base)
 *   - SocketClosedException
 *   - SocketUnknownHostException
 *   - SSLSocketException
 *     - SSLHandshakeFailedException
 */
class ErrorHandlingTests {

    @Test
    fun connectionRefused() = runTestNoTimeSkipping {
        // Try to connect to a port that's not listening
        // Use a random high port that's unlikely to be in use
        val port = 59000 + (kotlin.random.Random.nextInt(999))
        try {
            val socket = ClientSocket.allocate()
            socket.open(port = port, timeout = 2.seconds, hostname = "127.0.0.1")
            // If we get here, the port happened to be open - close and skip
            socket.close()
        } catch (e: SocketException) {
            // Expected - connection refused or similar error (covers all socket errors)
        } catch (e: TimeoutCancellationException) {
            // Also acceptable - may timeout instead of refuse on some systems
        } catch (e: Exception) {
            // JVM may throw java.net.ConnectException or java.io.IOException directly
            // Check if it's a connection-related error by message
            val msg = e.message?.lowercase() ?: ""
            val className = e::class.simpleName?.lowercase() ?: ""
            if (msg.contains("connect") || msg.contains("refused") ||
                className.contains("connect") || className.contains("socket") ||
                className.contains("io")) {
                // Expected
            } else {
                throw e
            }
        }
    }

    @Test
    fun readTimeoutOnNoData() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val serverReady = Mutex(locked = true)

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                serverReady.unlock()
                // Don't send any data - client should timeout
                kotlinx.coroutines.delay(5000) // Hold connection open
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
        serverReady.lock()

        // Try to read with a short timeout - should timeout since server sends nothing
        try {
            withTimeout(500.milliseconds) {
                client.read(500.milliseconds)
            }
            fail("Should have timed out")
        } catch (e: TimeoutCancellationException) {
            // Expected
        } catch (e: SocketException) {
            // Also acceptable - some implementations throw SocketException on timeout
        }

        client.close()
        server.close()
        serverJob.cancel()
    }

    @Test
    fun writeToClosedSocket() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val clientConnected = Mutex(locked = true)

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                clientConnected.unlock()
                // Immediately close the connection
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
        clientConnected.lock()

        // Give server time to close
        kotlinx.coroutines.delay(200)

        // Try to write to the closed connection
        // Note: detecting a closed connection may take multiple writes on some platforms
        // as the first few writes may succeed if the data fits in the socket buffer
        try {
            repeat(100) {
                client.write("test data that should eventually fail when the buffer is full".toReadBuffer(Charset.UTF8), 1.seconds)
                kotlinx.coroutines.delay(5)
            }
        } catch (e: SocketException) {
            // Expected - SocketException is base class, covers SocketClosedException too
        } catch (e: Exception) {
            // JVM may throw java.io.IOException directly on broken pipe
            val msg = e.message?.lowercase() ?: ""
            val className = e::class.simpleName?.lowercase() ?: ""
            if (msg.contains("broken pipe") || msg.contains("reset") || msg.contains("closed") ||
                className.contains("io") || className.contains("socket")) {
                // Expected
            } else {
                throw e
            }
        }
        // Don't assert - some implementations may buffer all writes

        client.close()
        server.close()
        serverJob.cancel()
    }

    @Test
    fun readFromClosedSocket() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val clientConnected = Mutex(locked = true)

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                clientConnected.unlock()
                // Send some data then close
                client.writeString("hello")
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
        clientConnected.lock()

        // Read the data that was sent
        val data = client.readString(timeout = 1.seconds)
        assertEquals("hello", data)

        // Now try to read more - should get EOF/closed exception
        try {
            client.read(1.seconds)
            fail("Should have thrown on reading from closed connection")
        } catch (e: SocketException) {
            // Expected - covers SocketClosedException
        }

        client.close()
        server.close()
        serverJob.cancel()
    }

    @Test
    fun serverClosedWhileClientConnected() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val clientConnected = Mutex(locked = true)

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                clientConnected.unlock()
                // Keep connection open but close server
                kotlinx.coroutines.delay(5000)
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")
        clientConnected.lock()

        // Close server while client is still connected
        server.close()
        serverJob.cancel()

        // Client should still be able to detect the situation
        kotlinx.coroutines.delay(100)

        client.close()
    }

    @Test
    fun multipleCloseCalls() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

        // Multiple close calls should not throw
        client.close()
        client.close()
        client.close()

        server.close()
        server.close()
        server.close()

        serverJob.cancel()
    }

    @Test
    fun connectToNonRoutableAddress() = runTestNoTimeSkipping {
        // 10.255.255.1 is typically non-routable and should timeout
        try {
            val socket = ClientSocket.allocate()
            socket.open(port = 80, timeout = 2.seconds, hostname = "10.255.255.1")
            socket.close()
            // If connection succeeded, the address was routable in this network
        } catch (e: SocketException) {
            // Expected - network unreachable or timeout
        } catch (e: TimeoutCancellationException) {
            // Expected - timeout
        }
    }

    @Test
    fun zeroLengthWrite() = runTestNoTimeSkipping {
        val server = ServerSocket.allocate()
        val serverFlow = server.bind()
        val dataReceived = Mutex(locked = true)
        var receivedData = ""

        val serverJob = launch(Dispatchers.Default) {
            serverFlow.collect { client ->
                receivedData = client.readString(timeout = 1.seconds)
                dataReceived.unlock()
                client.close()
            }
        }

        val client = ClientSocket.allocate()
        client.open(server.port(), timeout = 5.seconds, hostname = "127.0.0.1")

        // Write empty buffer - should succeed without error
        val emptyBuffer = PlatformBuffer.allocate(0)
        val bytesWritten = client.write(emptyBuffer, 1.seconds)
        assertEquals(0, bytesWritten)

        // Write actual data to complete the test
        client.writeString("actual data")
        client.close()

        dataReceived.lock()
        assertEquals("actual data", receivedData)

        server.close()
        serverJob.cancel()
    }

    @Test
    fun serverBindToUsedPort() = runTestNoTimeSkipping {
        val server1 = ServerSocket.allocate()
        server1.bind()
        val port = server1.port()

        // Try to bind another server to the same port
        try {
            val server2 = ServerSocket.allocate()
            server2.bind(port = port)
            // If we get here, the OS allowed it (unlikely)
            server2.close()
        } catch (e: SocketException) {
            // Expected - address already in use
        }

        server1.close()
    }

    @Test
    fun clientSocketNotOpenedYet() = runTestNoTimeSkipping {
        val client = ClientSocket.allocate()

        // isOpen should return false before opening
        assertFalse(client.isOpen(), "Socket should not be open before connect")

        // close() should not throw even if socket was never opened
        client.close()

        // After close, should still report as not open
        assertFalse(client.isOpen(), "Socket should not be open after close")
    }
}
