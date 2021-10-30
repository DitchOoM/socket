@file:Suppress("EXPERIMENTAL_UNSIGNED_LITERALS", "EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import block
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class SimpleSocketTests {

    @Test
    fun httpRawSocket() = block {
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
        val dataReceivedFromClient = serverToClient.read { buffer, bytesRead ->
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
            clientToServer.read { buffer, bytesRead -> buffer.readUtf8(bytesRead.toUInt()) }
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
    fun clientEchoSuspendingInputStream() = block {
        val server = asyncServerSocket()
        server.bind()
        val clientToServer = asyncClientSocket()
        val text1 = "yolo swag "
        val text2 = "lyfestyle"
        val text = text1 + text2
        val serverPort = assertNotNull(server.port(), "No port number from server")
        lateinit var serverToClient: ClientSocket
        launch {
            serverToClient = server.accept()
            serverToClient.write(text1)
            serverToClient.write(text2)
        }
        clientToServer.open(serverPort)
        val inputStream = clientToServer.suspendingInputStream(this, bufferSize = (text.length / 2).toUInt())

        delay(20)
        val buffer = inputStream.sizedReadBuffer(text.length).slice()
        val utf8 = buffer.readUtf8(text.length)
        val dataReceivedFromServer = utf8.toString()
        val serverToClientPort = assertNotNull(serverToClient.localPort())
        val clientToServerPort = assertNotNull(clientToServer.localPort())
        println("1")
        serverToClient.close()
        println("2")
        clientToServer.close()
        println("3")
        server.close()
        println("4")
        checkPort(clientToServerPort)
        println("5")
        checkPort(serverToClientPort)
        println("6")
        checkPort(serverPort)

        println("7")
        assertEquals(text, dataReceivedFromServer)
        println("done")
    }

    @ExperimentalUnsignedTypes
    private suspend fun checkPort(port: UShort) {
        val stats = readStats(port, "CLOSE_WAIT")
        if (stats.isNotEmpty()) {
            println("stats (${stats.count()}): $stats")
        }
        assertEquals(0, stats.count(), "Socket still in CLOSE_WAIT state found!")
    }
}