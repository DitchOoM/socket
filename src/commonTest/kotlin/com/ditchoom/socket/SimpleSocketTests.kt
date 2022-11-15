package com.ditchoom.socket

import block
import com.ditchoom.buffer.toBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimpleSocketTests {

    @Test
    fun connectTimeoutWorks() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example.com", timeout = 40.milliseconds)
                fail("should not have reached this")
            } catch (e: TimeoutCancellationException) {
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example.com", timeout = 40.milliseconds)
            }
        }
    }

    @Test
    fun invalidHost() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example234asdfa.com", timeout = 40.milliseconds)
                fail("should not have reached this")
            } catch (e: SocketUnknownHostException) {
                // expected
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example234asdfa.com", timeout = 40.milliseconds)
            }
        }
    }

    @Test
    fun closeWorks() = block {
        if (getNetworkCapabilities() == NetworkCapabilities.FULL_SOCKET_ACCESS) {
            try {
                ClientSocket.connect(3, hostname = "example.com", timeout = 40.milliseconds)
                fail("the port is invalid, so this line should never hit")
            } catch (t: TimeoutCancellationException) {
                // expected
            }
        } else {
            assertFailsWith<UnsupportedOperationException> {
                ClientSocket.connect(3, hostname = "example.com", timeout = 40.milliseconds)
            }
        }
    }

    @Test
    fun httpRawSocket() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        var localPort = 1
        val response = ClientSocket.connect(80, hostname = "example.com") { socket ->
            val request =
                """
GET / HTTP/1.1
Host: example.com
Connection: close

""".toBuffer()
            val bytesWritten = socket.write(request, 1.seconds)
            localPort = socket.localPort()
            assertTrue { bytesWritten > 0 }
            val buffer = socket.read(1.seconds)
            buffer.resetForRead()
            buffer.readUtf8(buffer.remaining()).toString()
        }
        assertTrue { response.contains("200 OK") }
        assertTrue { response.contains("HTTP") }
        assertTrue { response.contains("<html>") }
        assertNotEquals(1, localPort)
        checkPort(localPort)
    }

    @Test
    fun httpsRawSocket() = block {
        val domain = "example.com"
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        var localPort = 1
        val response = ClientSocket.connect(443, domain, tls = true) { socket ->
            val request =
                """
GET / HTTP/1.1
Host: www.$domain
Connection: close

""".toBuffer()
            val bytesWritten = socket.write(request, 5.seconds)
            localPort = socket.localPort()
            assertTrue { bytesWritten > 0 }
            val buffer = socket.read(5.seconds)
            buffer.resetForRead()
            var s = buffer.readUtf8(buffer.remaining()).toString()
            try {
                while (socket.isOpen()) {
                    val buffer2 = socket.read(5.seconds)
                    buffer2.resetForRead()
                    val response2 = buffer2.readUtf8(buffer2.remaining())
                    s += response2
                }
            } catch (e: SocketClosedException) {
                // expected when the server has Connection: close header
            }
            s
        }
        assertTrue { response.startsWith("HTTP/") }
        assertTrue { response.contains("html>", ignoreCase = true) }
        assertTrue { response.contains("/html>", ignoreCase = true) }
        assertNotEquals(1, localPort)
        checkPort(localPort)
    }

    @Test
    fun serverEcho() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val server = ServerSocket.allocate()
        server.bind()
        val text = "yolo swag lyfestyle"
        val serverPort = assertNotNull(server.port(), "No port number from server")
        val clientToServer = ClientSocket.allocate()
        launch(Dispatchers.Unconfined) {
            clientToServer.open(serverPort)
            clientToServer.write(text.toBuffer(), 1.seconds)
        }
        val serverToClient = server.accept()
        val buffer = serverToClient.read(1.seconds)
        buffer.resetForRead()
        val dataReceivedFromClient = buffer.readUtf8(buffer.remaining())
        assertEquals(text, dataReceivedFromClient.toString())
        val serverToClientPort = assertNotNull(serverToClient.localPort())
        val clientToServerPort = assertNotNull(clientToServer.localPort())
        serverToClient.close()
        clientToServer.close()
        server.close()
        checkPort(serverToClientPort)
        checkPort(clientToServerPort)
        checkPort(serverPort)
    }

    @Test
    fun clientEcho() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val server = ServerSocket.allocate()
        server.bind()
        val clientToServer = ClientSocket.allocate()
        val text = "yolo swag lyfestyle"
        val serverPort = assertNotNull(server.port(), "No port number from server")
        lateinit var serverToClient: ClientSocket
        launch {
            serverToClient = server.accept()
            serverToClient.write(text.toBuffer(), 1.seconds)
        }
        clientToServer.open(serverPort)
        val dataReceivedFromServer = withContext(Dispatchers.Default) {
            val buffer = clientToServer.read(1.seconds)
            buffer.resetForRead()
            buffer.readUtf8(buffer.remaining())
        }
        assertEquals(text, dataReceivedFromServer.toString())
        val serverToClientPort = assertNotNull(serverToClient.localPort())
        val clientToServerPort = assertNotNull(clientToServer.localPort())
        serverToClient.close()
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
        server.bind()
        val text = "yolo swag lyfestyle"
        val serverPort = assertNotNull(server.port(), "No port number from server")
        launch(Dispatchers.Default) {
            val clientToServer = ClientSocket.allocate()
            clientToServer.open(serverPort)
            val inputStream = SuspendingSocketInputStream(1.seconds, clientToServer, 8096)
            val buffer = inputStream.sizedReadBuffer(text.length).slice()
            val utf8 = buffer.readUtf8(text.length)
            assertEquals(utf8.toString(), text)
            val clientToServerPort = assertNotNull(clientToServer.localPort())
            clientToServer.close()
            checkPort(clientToServerPort)
            checkPort(serverPort)
        }
        val serverToClient = server.accept()
        val serverToClientPort = assertNotNull(serverToClient.localPort())
        serverToClient.write(text.toBuffer(), 1.seconds)
        serverToClient.close()
        server.close()
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
