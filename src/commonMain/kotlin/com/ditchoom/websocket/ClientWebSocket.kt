package com.ditchoom.websocket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.socket.*
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
class ClientWebSocket(
    private val webSocket: SocketController,
    private val reader: WebSocketFrameReader,
    private val writer: WebSocketFrameWriter,
): SocketController {


    override fun isOpen(): Boolean = webSocket.isOpen()

    override suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration) {
        writer.write(buffer)
    }

    override suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> {
        val buffer = reader.read() ?: emptyBuffer
        return SocketDataRead(buffer, buffer.remaining().toInt())
    }

    override suspend fun close(): Unit = webSocket.close()

    companion object {
        private val emptyBuffer = allocateNewBuffer(0u)

        suspend fun open(
            scope: CoroutineScope,
            connectionOptions: WebSocketConnectionOptions,
        ) : ClientWebSocket {
            val socketController = getClientSocket()
            socketController.open(connectionOptions.port.toUShort(), connectionOptions.connectionTimeout, connectionOptions.name)
            val request =
                "GET ${connectionOptions.websocketEndpoint} HTTP/1.1" +
                        "\r\nHost: ${connectionOptions.name}:${connectionOptions.port}" +
                        "\r\nUpgrade: websocket" +
                        "\r\nConnection: Upgrade" +
                        "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" +
                        "\r\nSec-WebSocket-Protocol: ${connectionOptions.protocol}" +
                        "\r\nSec-WebSocket-Version: 13" +
                        "\r\n\r\n"
            socketController.write(request, connectionOptions.connectionTimeout)
            val socketDataRead = socketController.readBuffer(connectionOptions.readTimeout)
            val response = socketDataRead.result.readUtf8(socketDataRead.bytesRead)
            if (!(response.contains("101 Switching Protocols", ignoreCase = true)
                        && response.contains("Upgrade: websocket", ignoreCase = true)
                        && response.contains("Connection: Upgrade", ignoreCase = true)
                        && response.contains("Sec-WebSocket-Accept", ignoreCase = true))
            ) {
                throw IllegalStateException("Invalid response from server when reading the result from websockets. Response:\r\n$response")
            }
            println(response)
            val bufferedReader = BufferedReader(socketController, connectionOptions.readTimeout)
            val suspendingInputStream = SuspendingSocketInputStream(scope, bufferedReader)
            val reader = WebSocketFrameReader(suspendingInputStream)
            val writer = WebSocketFrameWriter(socketController, connectionOptions.writeTimeout)
            return ClientWebSocket(socketController, reader, writer)
        }
    }
}