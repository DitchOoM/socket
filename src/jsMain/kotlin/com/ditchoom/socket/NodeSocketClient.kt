package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration

open class NodeSocket : ClientSocket {
    internal var isClosed = true
    internal lateinit var netSocket: Socket
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>()

    override fun isOpen() = !isClosed && netSocket.remoteAddress != null

    override suspend fun localPort() = netSocket.localPort

    override suspend fun remotePort() = netSocket.remotePort

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!isOpen()) {
            throw SocketException("Socket is closed")
        }
        netSocket.resume()
        val message = incomingMessageChannel.receive()
        message.result.position(message.bytesRead)
        return message.result
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        if (!isOpen()) {
            throw SocketException("Socket is closed")
        }
        val array = (buffer as JsBuffer).buffer
        netSocket.write(array)
        return array.byteLength
    }

    fun cleanSocket(netSocket: Socket) {
        isClosed = true
        incomingMessageChannel.close()
        netSocket.end {}
        netSocket.destroy()
    }

    override suspend fun close() {
        cleanSocket(netSocket)
    }
}

class NodeClientSocket(private val bufferFactory: () -> PlatformBuffer) : NodeSocket(), ClientToServerSocket {

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions = withTimeout(timeout) {
        val arrayPlatformBufferMap = HashMap<Uint8Array, JsBuffer>()
        val onRead = OnRead({
            val buffer = bufferFactory() as JsBuffer
            arrayPlatformBufferMap[buffer.buffer] = buffer
            buffer.buffer
        }, { bytesRead, buffer ->
            val platformBuffer = arrayPlatformBufferMap.remove(buffer)!!
            platformBuffer.setLimit(bytesRead)
            val socketDataRead = SocketDataRead(platformBuffer.slice(), bytesRead)
            incomingMessageChannel.trySend(socketDataRead)
            false
        })
        val options = tcpOptions(port, hostname, onRead)
        val netSocket = connect(options) { socket, throwable ->
            cleanSocket(socket)
        }
        isClosed = false
        this@NodeClientSocket.netSocket = netSocket
        netSocket.on("close") { ->
            cleanSocket(netSocket)
        }
        socketOptions ?: SocketOptions()
    }
}
