@file:Suppress("EXPERIMENTAL_UNSIGNED_LITERALS", "EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import block
import com.ditchoom.buffer.toBuffer
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketDataRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
class SimpleSocketTests {

    @Test
    fun websocket() = block {
        val webSocketConnectionOptions = WebSocketConnectionOptions(
            "localhost",
            8080,
            "echo",
            "/echo",
            seconds(1)
        )
        val websocketClient = getWebSocketClient(webSocketConnectionOptions)
        val stringToValidate = "test"
        websocketClient.write(stringToValidate.toBuffer())
        withContext(Dispatchers.Default) {
            val dataRead = websocketClient.read()
            assertTrue(dataRead is WebSocketDataRead.BinaryWebSocketDataRead)
            val stringData = dataRead.data.readUtf8(dataRead.data.limit()).toString()
            assertEquals(stringToValidate, stringData)
        }
        websocketClient.close()
    }

    @Test
    fun httpRawSocket() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val client = openClientSocket(80u, hostname = "example.com")
        val request =
            """
GET / HTTP/1.1
Host: example.com
Connection: close

"""
        val bytesWritten = client.write(request)
        assertTrue { bytesWritten > 0 }
        val response = client.read().result
        client.close()
        assertTrue { response.contains("200 OK") }
        assertTrue { response.contains("HTTP") }
        assertTrue { response.contains("<html>") }
    }

    @Test
    fun serverEcho() = block {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
        val server = asyncServerSocket()
        server.bind()
        val text = "yolo swag lyfestyle"
        val serverPort = assertNotNull(server.port(), "No port number from server")
        val clientToServer = asyncClientSocket()
        launch(Dispatchers.Unconfined) {
            clientToServer.open(serverPort)
            clientToServer.write(text)
        }
        val serverToClient = server.accept()
        val dataReceivedFromClient = serverToClient.read() { buffer, bytesRead ->
            buffer.readUtf8(bytesRead.toUInt())
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
        val server = asyncServerSocket()
        server.bind()
        val clientToServer = asyncClientSocket()
        val text = "yolo swag lyfestyle"
        val serverPort = assertNotNull(server.port(), "No port number from server")
        lateinit var serverToClient: ClientSocket
        launch {
            serverToClient = server.accept()
            serverToClient.write(text)
        }
        clientToServer.open(serverPort)
        val dataReceivedFromServer = withContext(Dispatchers.Default) {
            clientToServer.read() { buffer, bytesRead ->
                buffer.readUtf8(bytesRead.toUInt())
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

//    @Test
//    fun suspendingInputStream() = block {
//        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@block
//        val server = asyncServerSocket()
//        server.bind()
//        val text = "yolo swag lyfestyle"
//        val serverPort = assertNotNull(server.port(), "No port number from server")
//        launch(Dispatchers.Default) {
//            val clientToServer = asyncClientSocket()
//            clientToServer.open(serverPort)
//            val inputStream = clientToServer.suspendingInputStream(this)
//            val buffer = inputStream.sizedReadBuffer(text.length).slice()
//            val utf8 = buffer.readUtf8(text.length)
//            assertEquals(utf8.toString(), text)
//            val clientToServerPort = assertNotNull(clientToServer.localPort())
//            inputStream.close()
//            delay(5)
//            checkPort(clientToServerPort)
//            checkPort(serverPort)
//            // Needed for native tests, not sure why
//            cancel()
//        }
//        val serverToClient = server.accept()
//        val serverToClientPort = assertNotNull(serverToClient.localPort())
//        serverToClient.write(text)
//        serverToClient.close()
//        server.close()
//        delay(15) // needed for jvm, not sure why
//        checkPort(serverToClientPort)
//    }

    @ExperimentalUnsignedTypes
    private suspend fun checkPort(port: UShort) {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return
        val stats = readStats(port, "CLOSE_WAIT")
        if (stats.isNotEmpty()) {
            println("stats (${stats.count()}): $stats")
        }
        assertEquals(0, stats.count(), "Socket still in CLOSE_WAIT state found!")
    }
}