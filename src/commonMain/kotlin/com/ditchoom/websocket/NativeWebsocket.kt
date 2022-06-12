package com.ditchoom.websocket

import com.ditchoom.buffer.*
import com.ditchoom.data.get
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SuspendingSocketInputStream
import com.ditchoom.socket.getClientSocket
import com.ditchoom.websocket.MaskingKey.FourByteMaskingKey
import com.ditchoom.websocket.MaskingKey.NoMaskingKey
import kotlin.experimental.xor
import kotlin.time.Duration

class NativeWebsocket(private val connectionOptions: WebSocketConnectionOptions, private val socket: ClientSocket) :
    WebSocket {
    private val inputStream = SuspendingSocketInputStream(connectionOptions.readTimeout, socket)

    override fun isOpen() = socket.isOpen()

    override suspend fun write(buffer: PlatformBuffer) {
        val t = WebSocketClientToServerBinaryFrameTransformer.transform(buffer)
        t.position(t.limit().toInt())
        socket.write(t, connectionOptions.writeTimeout)
    }

    override suspend fun write(string: String) {
        socket.write(WebSocketClientToServerTextFrameTransformer.transform(string), connectionOptions.writeTimeout)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        val limit = buffer.limit().toInt()
        write(buffer)
        return limit
    }

    override suspend fun ping() {
        socket.write(WebSocketFrame(Opcode.Ping).toBuffer(), connectionOptions.writeTimeout)
    }

    override suspend fun readData(timeout: Duration): ReadBuffer {
        val frame = readWebSocketFrame()
        var payload: ReadBuffer = frame.payloadData
        if (!frame.fin) {
            payload = FragmentedReadBuffer(payload, readData(timeout)).slice()
        }
        return payload
    }

    override suspend fun read(): WebSocketDataRead {
        val frame = readWebSocketFrame()
        return when (frame.opcode) {
            Opcode.Binary -> {
                WebSocketDataRead.BinaryWebSocketDataRead(frame.payloadData)
            }
            Opcode.Text -> {
                WebSocketDataRead.CharSequenceWebSocketDataRead(frame.payloadData.readUtf8(frame.payloadData.limit()))
            }
            Opcode.Ping -> {
                write(WebSocketFrame(Opcode.Pong, frame.payloadData).toBuffer())
                WebSocketDataRead.OtherOpCodeWebSocketDataRead(frame.opcode, frame.payloadData)
            }
            else -> WebSocketDataRead.OtherOpCodeWebSocketDataRead(frame.opcode, frame.payloadData)
        }
    }

    private suspend fun readWebSocketFrame(): WebSocketFrame {
        val byte1 = inputStream.readByte()
        val fin = byte1[0]
        val rsv1 = byte1[1]
        val rsv2 = byte1[2]
        val rsv3 = byte1[3]
        val opcode = Opcode.from(byte1)
        val maskAndPayloadLengthByte = inputStream.readByte()
        val mask = maskAndPayloadLengthByte[0]
        val payloadLength = maskAndPayloadLengthByte.toInt().shl(1).shr(1)
        val actualPayloadLength = if (payloadLength <= 125) {
            payloadLength.toULong()
        } else if (payloadLength == 126) {
            inputStream.sizedReadBuffer(UShort.SIZE_BYTES).readUnsignedShort().toULong()
        } else if (payloadLength == 127) {
            inputStream.sizedReadBuffer(ULong.SIZE_BYTES).readUnsignedLong()
        } else {
            throw IllegalArgumentException("Invalid payload length")
        }
        val maskingKey = if (mask) {
            FourByteMaskingKey(inputStream.sizedReadBuffer(Int.SIZE_BYTES).readByteArray(Int.SIZE_BYTES))
        } else {
            NoMaskingKey
        }
        val payload = if (actualPayloadLength == 0uL) {
            PlatformBuffer.allocate(0)
        } else {
            check(actualPayloadLength < Int.MAX_VALUE.toULong()) { "Payloads larger than ${Int.MAX_VALUE} is currently unsupported" }
            val platformBuffer = PlatformBuffer.allocate(actualPayloadLength.toInt())
            val originalReadBuffer = inputStream.sizedReadBuffer(actualPayloadLength.toInt())
            val bytes = originalReadBuffer.readByteArray(actualPayloadLength.toInt())
            val readBuffer = PlatformBuffer.allocate(actualPayloadLength.toInt())
            readBuffer.write(bytes)
            readBuffer.resetForRead()
            val transformedBuffer = if (maskingKey is FourByteMaskingKey) {
                TransformedReadBuffer(readBuffer) { i, original ->
                    original xor maskingKey.maskingKey[i.toLong().mod(4)]
                }
            } else {
                readBuffer
            }
            platformBuffer.write(transformedBuffer)
            platformBuffer.resetForRead()
            platformBuffer
        }
        return WebSocketFrame(fin, rsv1, rsv2, rsv3, opcode, maskingKey, payload)
    }

    override suspend fun awaitClose() = socket.awaitClose()

    override suspend fun close() {
        socket.close()
    }

    companion object {
        suspend fun open(connectionOptions: WebSocketConnectionOptions): NativeWebsocket {
            val socket = getClientSocket()
            socket.open(connectionOptions.port.toUShort(), connectionOptions.connectionTimeout, connectionOptions.name)
            var request =
                "GET ${connectionOptions.websocketEndpoint} HTTP/1.1" +
                        "\r\nHost: ${connectionOptions.name}:${connectionOptions.port}" +
                        "\r\nUpgrade: websocket" +
                        "\r\nConnection: Upgrade" +
                        "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
            if (connectionOptions.protocol != null) {
                request += "\r\nSec-WebSocket-Protocol: ${connectionOptions.protocol}"
            }
            request += "\r\nSec-WebSocket-Version: 13" + "\r\n\r\n"
            socket.write(request, connectionOptions.writeTimeout)
            val socketDataRead = socket.readBuffer(connectionOptions.readTimeout)
            val response = socketDataRead.result.readUtf8(socketDataRead.bytesRead)
            if (!(response.contains("101 Switching Protocols", ignoreCase = true)
                        && response.contains("Upgrade: websocket", ignoreCase = true)
                        && response.contains("Connection: Upgrade", ignoreCase = true)
                        && response.contains("Sec-WebSocket-Accept", ignoreCase = true))
            ) {
                throw IllegalStateException("Invalid response from server when reading the result from websockets. Response:\r\n$response")
            }
            return NativeWebsocket(connectionOptions, socket)
        }
    }
}