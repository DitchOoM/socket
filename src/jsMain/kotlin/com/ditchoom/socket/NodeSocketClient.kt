package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration

open class NodeSocket : ClientSocket {
    internal var isClosed = true
    internal lateinit var netSocket: Socket
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>()
    internal var hadTransmissionError = false
    private val writeMutex = Mutex()
    override fun isOpen() = !isClosed || netSocket.remoteAddress != null

    override suspend fun localPort() = netSocket.localPort

    override suspend fun remotePort() = netSocket.remotePort

    override suspend fun read(timeout: Duration): ReadBuffer {
        if (!isOpen()) {
            throw SocketClosedException("Socket closed. transmissionError=$hadTransmissionError")
        }
        netSocket.resume()
        val message = withTimeout(timeout) {
            try {
                incomingMessageChannel.receive()
            } catch (e: ClosedReceiveChannelException) {
                throw SocketClosedException(
                    "Socket is already closed. transmissionError=$hadTransmissionError",
                    e
                )
            }
        }
        if (message.bytesRead < 0 || !isOpen()) {
            throw SocketClosedException("Received ${message.bytesRead} from server indicating a socket close. transmissionError=$hadTransmissionError")
        }
        message.result.position(message.bytesRead)
        return message.result
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        if (!isOpen()) {
            throw SocketException("Socket is closed. transmissionError=$hadTransmissionError")
        }
        val array = (buffer as JsBuffer).buffer
        writeMutex.withLock { netSocket.write(array) }
        return array.byteLength
    }

    fun cleanSocket(netSocket: Socket) {
        incomingMessageChannel.close()
        netSocket.end {}
        netSocket.destroy()
        isClosed = true
    }

    override suspend fun close() {
        cleanSocket(netSocket)
    }
}

class NodeClientSocket(
    private val useTls: Boolean,
    private val allocationZone: AllocationZone
) : NodeSocket(), ClientToServerSocket {

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?
    ) = withTimeout(timeout) {
        val arrayPlatformBufferMap = HashMap<Uint8Array, JsBuffer>()
        val onRead = OnRead({
            val buffer = PlatformBuffer.allocate(8192, allocationZone) as JsBuffer
            arrayPlatformBufferMap[buffer.buffer] = buffer
            buffer.buffer
        }, { bytesRead, buffer ->
            val platformBuffer = arrayPlatformBufferMap.remove(buffer)!!
            platformBuffer.setLimit(bytesRead)
            val socketDataRead = SocketDataRead(platformBuffer.slice(), bytesRead)
            incomingMessageChannel.trySend(socketDataRead)
            false
        })
        val options = Options(port, hostname, onRead, rejectUnauthorized = false)
        val netSocket = connect(useTls, options)
        isClosed = false
        this@NodeClientSocket.netSocket = netSocket
        netSocket.on("close") { transmissionError ->
            hadTransmissionError = transmissionError.unsafeCast<Boolean>()
            cleanSocket(netSocket)
        }
    }
}
