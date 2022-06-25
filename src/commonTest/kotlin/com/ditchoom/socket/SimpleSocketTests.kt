package com.ditchoom.socket

import block
import com.ditchoom.buffer.toBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketDataRead
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimpleSocketTests {

    @Test
    fun mock() = block {
        val server = MockServerSocket()
        server.bind()
        // test two in parallel
        val client1 = testMockClientAsync(server)
        val client2 = testMockClientAsync(server)
        awaitAll(client1, client2)
    }

    @Test
    fun mockAwaitClose() = block {
        val server = MockServerSocket()
        server.bind()
        val client = testMockClientAsync(server)
        awaitCloseWorks(client.await())
    }

    private suspend fun testMockClientAsync(server: MockServerSocket) =
        coroutineScope {
            async {
                launch {
                    val serverToClientSocket = server.accept()
                    assertEquals("hello", serverToClientSocket.read().result.toString())
                    serverToClientSocket.write("meow")
                }
                val client = MockClientSocket()
                client.open(server.port()!!)
                client.write("hello")
                assertEquals("meow", client.read().result.toString())
                client
            }
        }

    @Test
    fun websocket() = block {
        val stringToValidate = "test"
        val buffer = stringToValidate.toBuffer()

        val webSocketConnectionOptions = WebSocketConnectionOptions(
            "localhost",
            8080,
            websocketEndpoint = "/echo",
            connectionTimeout = 1.seconds,
        )
        val websocketClient = getWebSocketClient(webSocketConnectionOptions)
        websocketClient.write(buffer)
        val dataRead = websocketClient.read()
        assertTrue(dataRead is WebSocketDataRead.BinaryWebSocketDataRead)
        val stringData = dataRead.data.readUtf8(dataRead.data.limit()).toString()
        assertEquals(stringToValidate, stringData)
        websocketClient.close()
    }

    @Test
    fun connectTimeoutWorks() = block {
        try {
            ClientSocket.connect(3, hostname = "example.com", timeout = 100.milliseconds)
            fail("should have timed out")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun awaitCloseWorks() = block {
        val client = ClientSocket.connect(80, hostname = "example.com", timeout = 100.milliseconds)
        awaitCloseWorks(client)
    }

    suspend fun awaitCloseWorks(client: ClientSocket) = coroutineScope {
        var count = 0
        val closeAsyncJob = async {
            client.close()
            count++
        }
        client.awaitClose()
        closeAsyncJob.await()
        assertEquals(1, count)
    }


    @Test
    fun httpRawSocket() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val response = ClientSocket.connect(80, hostname = "example.com") { socket ->
            val request =
                """
GET / HTTP/1.1
Host: example.com
Connection: close

"""
            val bytesWritten = socket.write(request)
            assertTrue { bytesWritten > 0 }
            socket.read().result
        }
        assertTrue { response.contains("200 OK") }
        assertTrue { response.contains("HTTP") }
        assertTrue { response.contains("<html>") }
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
            clientToServer.write(text)
        }
        val serverToClient = server.accept()
        val dataReceivedFromClient = serverToClient.read { buffer, bytesRead ->
            buffer.readUtf8(bytesRead)
        }
        assertEquals(text, dataReceivedFromClient.result.toString())
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
            serverToClient.write(text)
        }
        clientToServer.open(serverPort)
        val dataReceivedFromServer = withContext(Dispatchers.Default) {
            clientToServer.read { buffer, bytesRead ->
                buffer.readUtf8(bytesRead)
            }
        }
        assertEquals(text, dataReceivedFromServer.result.toString())
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
            val inputStream = SuspendingSocketInputStream(1.seconds, clientToServer)
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
        serverToClient.write(text)
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