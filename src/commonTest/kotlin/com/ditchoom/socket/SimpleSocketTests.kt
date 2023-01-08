package com.ditchoom.socket

import block
import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class SimpleSocketTests {

    @Test
    fun connectTimeoutWorks() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example.com", timeout = 1.seconds)
                fail("should not have reached this")
            } catch (_: TimeoutCancellationException) {
            } catch (_: SocketException) {
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example.com", timeout = 1.seconds)
            }
        }
    }

    @Test
    fun invalidHost() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example234asdfa.com", timeout = 1.seconds)
                fail("should not have reached this")
            } catch (e: SocketException) {
                // expected
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example234asdfa.com", timeout = 1.seconds)
            }
        }
    }

    @Test
    fun closeWorks() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example.com", timeout = 1.seconds)
                fail("the port is invalid, so this line should never hit")
            } catch (t: TimeoutCancellationException) {
                // expected
            } catch (s: SocketException) {
                // expected
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example.com", timeout = 1.seconds)
            }
        }
    }

    @Test
    fun httpRawSocketExampleDomain() = block {
        http("example.com", false)
    }

    @Test
    fun httpsRawSocketExampleDomain() = block {
        http("example.com", true)
    }

    @Test
    fun httpRawSocketGoogleDomain() {
        http("google.com", false)
    }

    @Test
    fun httpsRawSocketGoogleDomain() {
        http("google.com", true)
    }

    private fun http(domain: String, tls: Boolean) = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        var localPort = 1
        val remotePort = if (tls) 443 else 80
        val response = ClientSocket.connect(remotePort, domain, tls = tls) { socket ->
            val request =
                """
GET / HTTP/1.1
Host: www.$domain
Connection: close

""".toReadBuffer(Charset.UTF8, AllocationZone.Heap)
            val bytesWritten = socket.write(request, 5.seconds)
            localPort = socket.localPort()
            assertTrue { bytesWritten > 0 }
            val buffer = socket.readString(Charset.UTF8, 5.seconds)
            val charset = if (buffer.contains("charset=ISO-8859-1", ignoreCase = true)) {
                Charset.ISOLatin1
            } else if (buffer.contains("charset=ascii", ignoreCase = true)) {
                Charset.ASCII
            } else {
                Charset.UTF8
            }
            val s = StringBuilder(buffer)
            try {
                while (socket.isOpen()) {
                    val string = socket.readString(charset, 5.seconds)
                    s.append(string)
                }
            } catch (e: SocketClosedException) {
                // expected when the server has Connection: close header
            }
            s
        }
        assertTrue { response.startsWith("HTTP/") }
        assertTrue { response.contains("<html", ignoreCase = true) }
        assertTrue { response.contains("/html>", ignoreCase = true) }
        assertNotEquals(1, localPort)
        checkPort(localPort)
    }

    @Test
    fun serverEcho() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val server = ServerSocket.allocate()
        server.setScope(this)
        val text = "yolo swag lyfestyle"
        var serverToClientPort = 0
        val serverToClientMutex = Mutex(locked = true)
        server.start { serverToClient ->
            val buffer = serverToClient.read(1.seconds)
            buffer.resetForRead()
            val dataReceivedFromClient = buffer.readString(buffer.remaining(), Charset.UTF8)
            assertEquals(text, dataReceivedFromClient)
            serverToClientPort = serverToClient.localPort()
            assertTrue(serverToClientPort > 0, "No port number: serverToClientPort")
            serverToClient.close()
            serverToClientMutex.unlock()
        }
        val serverPort = server.port()
        assertTrue(serverPort > 0, "No port number from server")
        val clientToServer = ClientSocket.allocate()
        clientToServer.open(serverPort, 5.seconds)
        clientToServer.write(text.toReadBuffer(Charset.UTF8), 1.seconds)
        serverToClientMutex.lock()
        val clientToServerPort = clientToServer.localPort()
        assertTrue(clientToServerPort > 0, "Invalid clientToServerPort local port.")
        clientToServer.close()
        server.close()
        checkPort(serverToClientPort)
        checkPort(clientToServerPort)
        checkPort(serverPort)
    }

    @Test
    fun clientEcho() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val text = "yolo swag lyfestyle"
        val server = ServerSocket.allocate()
        server.setScope(this)
        var serverToClientPort = 0
        val serverToClientMutex = Mutex(locked = true)
        server.start { serverToClient ->
            serverToClientPort = serverToClient.localPort()
            assertTrue { serverToClientPort > 0 }
            serverToClient.write(text.toReadBuffer(Charset.UTF8), 5.seconds)
            serverToClient.close()
            serverToClientMutex.unlock()
        }
        val clientToServer = ClientSocket.allocate()
        val serverPort = server.port()
        assertTrue(serverPort > 0, "No port number from server")
        clientToServer.open(serverPort)
        val buffer = clientToServer.read(5.seconds)
        buffer.resetForRead()
        val dataReceivedFromServer = buffer.readString(buffer.remaining(), Charset.UTF8)
        serverToClientMutex.lock()
        assertEquals(text, dataReceivedFromServer)
        val clientToServerPort = clientToServer.localPort()
        assertTrue(clientToServerPort > 0, "No port number: clientToServerPort")
        clientToServer.close()
        server.close()
        checkPort(clientToServerPort)
        checkPort(serverToClientPort)
        checkPort(serverPort)
    }

    @Test
    fun suspendingInputStream() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val server = ServerSocket.allocate()
        server.setScope(this)
        val text = "yolo swag lyfestyle"
        var serverToClientPort = 0
        val serverToClientMutex = Mutex(locked = true)
        server.start { serverToClient ->
            serverToClientPort = serverToClient.localPort()
            assertTrue(serverToClientPort > 0, "No port number: serverToClientPort")
            serverToClient.write(text.toReadBuffer(Charset.UTF8), 1.seconds)
            serverToClient.close()
            serverToClientMutex.unlock()
        }
        val serverPort = server.port()
        assertTrue(serverPort > 0, "No port number from server")
        val clientToServer = ClientSocket.allocate()
        clientToServer.open(serverPort)
        val inputStream = SuspendingSocketInputStream(1.seconds, clientToServer, 8096)
        val buffer = inputStream.sizedReadBuffer(text.length).slice()
        serverToClientMutex.lock()
        val utf8 = buffer.readString(text.length, Charset.UTF8)
        assertEquals(utf8, text)
        val clientToServerPort = clientToServer.localPort()
        assertTrue(clientToServerPort > 0, "No port number from clientToServerPort")
        clientToServer.close()
        server.close()
        checkPort(clientToServerPort)
        checkPort(serverPort)
        checkPort(serverToClientPort)
    }

    private suspend fun checkPort(port: Int) {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return
        val stats = readStats(port, "CLOSE_WAIT")
        if (stats.isNotEmpty()) {
            println("stats (${stats.count()}): $stats")
        }
        assertEquals(0, stats.count(), "Socket still in CLOSE_WAIT state found!")
    }
}

expect suspend fun readStats(port: Int, contains: String): List<String>
