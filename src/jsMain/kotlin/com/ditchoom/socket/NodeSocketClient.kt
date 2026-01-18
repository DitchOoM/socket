package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration

open class NodeSocket : ClientSocket {
    internal var isClosed = true
    internal var netSocket: Socket? = null
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>()
    internal var hadTransmissionError = false
    private val writeMutex = Mutex()

    override fun isOpen(): Boolean {
        val socket = netSocket ?: return false
        return !isClosed || socket.remoteAddress != null
    }

    override suspend fun localPort() = netSocket?.localPort ?: -1

    override suspend fun remotePort() = netSocket?.remotePort ?: -1

    override suspend fun read(timeout: Duration): ReadBuffer {
        val socket = netSocket
        if (socket == null || !isOpen()) {
            throw SocketClosedException("Socket closed. transmissionError=$hadTransmissionError")
        }
        socket.resume()
        val message =
            withTimeout(timeout) {
                try {
                    incomingMessageChannel.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw SocketClosedException(
                        "Socket is already closed. transmissionError=$hadTransmissionError",
                        e,
                    )
                }
            }
        if (message.bytesRead < 0 || !isOpen()) {
            throw SocketClosedException(
                "Received ${message.bytesRead} from server indicating a socket close. transmissionError=$hadTransmissionError",
            )
        }
        message.result.position(message.bytesRead)
        return message.result
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val socket = netSocket
        if (socket == null || !isOpen()) {
            throw SocketException("Socket is closed. transmissionError=$hadTransmissionError")
        }
        val jsBuffer = buffer as JsBuffer
        val array = jsBuffer.buffer
        val bytesToWrite = buffer.remaining()
        // Create a view of only the bytes to write (from position to position + remaining)
        val dataToWrite = Uint8Array(array.buffer, array.byteOffset + buffer.position(), bytesToWrite)
        writeMutex.withLock { socket.write(dataToWrite) }
        buffer.position(buffer.position() + bytesToWrite)
        return bytesToWrite
    }

    fun cleanSocket(socket: Socket) {
        incomingMessageChannel.close()
        socket.end {}
        socket.destroy()
        isClosed = true
    }

    override suspend fun close() {
        val socket = netSocket ?: return
        cleanSocket(socket)
    }
}

class NodeClientSocket(
    private val useTls: Boolean,
    private val allocationZone: AllocationZone,
) : NodeSocket(),
    ClientToServerSocket {
    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
    ) = withTimeout(timeout) {
        val options = Options(port, hostname, onread = null, rejectUnauthorized = false)
        val netSocket = connect(useTls, options)
        isClosed = false
        this@NodeClientSocket.netSocket = netSocket
        netSocket.on("data") { data ->
            val result = int8ArrayOf(data)
            val buffer = JsBuffer(result)
            buffer.position(result.length)
            buffer.resetForRead()
            incomingMessageChannel.trySend(SocketDataRead(buffer, result.length))
        }
        netSocket.on("close") { transmissionError ->
            hadTransmissionError = transmissionError.unsafeCast<Boolean>()
            cleanSocket(netSocket)
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun int8ArrayOf(
        @Suppress("UNUSED_PARAMETER") obj: Any,
    ): Int8Array =
        js(
            """
            if (Buffer.isBuffer(obj)) {
                // Create a copy to avoid issues with Node.js Buffer pooling
                return new Int8Array(obj)
            } else {
                return new Int8Array(Buffer.from(obj))
            }
        """,
        ) as Int8Array
}
