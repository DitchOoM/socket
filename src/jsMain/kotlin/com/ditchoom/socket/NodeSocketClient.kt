@file:Suppress("EXPERIMENTAL_OVERRIDE")

package com.ditchoom.socket

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import kotlinx.coroutines.channels.Channel
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
open class NodeSocket : ClientSocket {
    var netSocket: Socket? = null
    internal val incomingMessageChannel = Channel<SocketDataRead<JsBuffer>>(1)

    override fun isOpen() = netSocket?.remoteAddress != null

    override fun localPort() = netSocket?.localPort?.toUShort()

    override fun remotePort() = netSocket?.remotePort?.toUShort()

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        val receivedData = incomingMessageChannel.receive()
        netSocket?.resume()
        buffer.put(receivedData.result)
        receivedData.result.put(buffer)
        return receivedData.bytesRead
    }
    override suspend fun <T> read(timeout: Duration, bufferSize: UInt, bufferRead: (PlatformBuffer, Int) -> T): SocketDataRead<T> {
        val receivedData = incomingMessageChannel.receive()
        netSocket?.resume()
        return SocketDataRead(bufferRead(receivedData.result, receivedData.bytesRead), receivedData.bytesRead)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        val array = (buffer as JsBuffer).buffer
        val netSocket = netSocket ?: return 0
        netSocket.write(array)
        return array.byteLength
    }

    override suspend fun close() {
        incomingMessageChannel.close()
        val socket = netSocket
        netSocket = null
        socket?.close()
    }
}

@ExperimentalTime
class NodeClientSocket : NodeSocket(), ClientToServerSocket {

    override suspend fun open(
        port: UShort,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        val arrayPlatformBufferMap = HashMap<Uint8Array, JsBuffer>()
        val onRead = OnRead({
            val buffer = allocateNewBuffer(8096u) as JsBuffer
            arrayPlatformBufferMap[buffer.buffer] = buffer
            buffer.buffer
        }, { bytesRead, buffer ->
            incomingMessageChannel.offer(SocketDataRead(arrayPlatformBufferMap.remove(buffer)!!, bytesRead))
            false
        })
        val options = tcpOptions(port.toInt(), hostname, onRead)
        val netSocket = connect(options)
        this.netSocket = netSocket
        netSocket.on("error") { err ->
            error(err.toString())
        }
        return SocketOptions()
    }
}
