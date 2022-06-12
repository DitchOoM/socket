@file:Suppress("EXPERIMENTAL_OVERRIDE", "EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.khronos.webgl.Uint8Array
import kotlin.time.Duration

open class NodeSocket(override val allocationZone: AllocationZone) : ClientSocket {
    internal var isClosed = true
    internal lateinit var netSocket: Socket
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>(Channel.UNLIMITED)
    private var currentBuffer: ReadBuffer? = null
    internal val disconnectedFlow =
        MutableSharedFlow<SocketException>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    protected var wasCloseInitiatedClientSize = false

    override fun isOpen() = !isClosed && netSocket.remoteAddress != null

    override suspend fun localPort() = netSocket.localPort.toUShort()

    override suspend fun remotePort() = netSocket.remotePort.toUShort()


    private suspend fun readBuffer(size: Int): SocketDataRead<ReadBuffer> {
        var currentBuffer = currentBuffer ?: incomingMessageChannel.receive().result
        var bytesLeft = currentBuffer.remaining()
        while (bytesLeft > 0) {
            val socketDataResult = incomingMessageChannel.receive()
            bytesLeft -= socketDataResult.bytesRead
            currentBuffer = FragmentedReadBuffer(currentBuffer, socketDataResult.result).slice()
        }
        this.currentBuffer = currentBuffer
        return SocketDataRead(currentBuffer, size)
    }

    override suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> {
        val msg = incomingMessageChannel.receive()
        netSocket.resume()
        return msg.copy(result = msg.result.slice())
    }

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        if (isClosed) {
            return -1
        }
        val receivedData = readBuffer(buffer.remaining())
        netSocket.resume()
        val resultBuffer = receivedData.result
        resultBuffer.position(0)
        return receivedData.bytesRead
    }

    override suspend fun <T> read(
        timeout: Duration,
        bufferSize: Int,
        bufferRead: (PlatformBuffer, Int) -> T
    ): SocketDataRead<T> {
        val receivedData = incomingMessageChannel.receive()
        netSocket.resume()
        val buffer = receivedData.result.slice() as PlatformBuffer
        return SocketDataRead(bufferRead(buffer, receivedData.bytesRead), receivedData.bytesRead)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        if (isClosed) return -1
        val array = (buffer as JsBuffer).buffer
        netSocket.write(array)
        return array.byteLength
    }

    override suspend fun awaitClose() = disconnectedFlow.asSharedFlow().first()

    fun cleanSocket(netSocket: Socket) {
        isClosed = true
        wasCloseInitiatedClientSize = true
        disconnectedFlow.tryEmit(SocketException("User closed socket", wasCloseInitiatedClientSize))
        try {
            incomingMessageChannel.close()
        } catch (t: Throwable) {
        }
        netSocket.end {}
        netSocket.destroy()
    }

    override suspend fun close() {
        cleanSocket(netSocket)
    }
}

class NodeClientSocket(zone: AllocationZone = AllocationZone.Direct) : NodeSocket(zone), ClientToServerSocket {

    override suspend fun open(
        port: UShort,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions = withTimeout(timeout) {
        val arrayPlatformBufferMap = HashMap<Uint8Array, JsBuffer>()
        val onRead = OnRead({
            val buffer = PlatformBuffer.allocate(4 * 1024, zone = allocationZone) as JsBuffer
            arrayPlatformBufferMap[buffer.buffer] = buffer
            buffer.buffer
        }, { bytesRead, buffer ->
            val platformBuffer = arrayPlatformBufferMap.remove(buffer)!!
            platformBuffer.setLimit(bytesRead)
            val socketDataRead = SocketDataRead(platformBuffer.slice(), bytesRead)
            incomingMessageChannel.trySend(socketDataRead)
            false
        })
        val options = tcpOptions(port.toInt(), hostname, onRead)
        val netSocket = try {
            connect(options) {
                cleanSocket(it)
            }
        } catch (e: TimeoutCancellationException) {
            throw e
        }
        isClosed = false
        this@NodeClientSocket.netSocket = netSocket
        netSocket.on("close") { ->
            cleanSocket(netSocket)
        }
        socketOptions ?: SocketOptions()
    }
}
