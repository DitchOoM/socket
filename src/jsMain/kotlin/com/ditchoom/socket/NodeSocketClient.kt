@file:Suppress("EXPERIMENTAL_OVERRIDE", "EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.khronos.webgl.Uint8Array
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
open class NodeSocket : ClientSocket {
    protected var isClosed = true
    internal lateinit var netSocket: Socket
    internal val incomingMessageChannel = Channel<SocketDataRead<ReadBuffer>>(Channel.UNLIMITED)
    private var currentBuffer :ReadBuffer? = null
    internal val disconnectedFlow = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun isOpen() = !isClosed && netSocket.remoteAddress != null

    override suspend fun localPort() = netSocket.localPort.toUShort()

    override suspend fun remotePort() = netSocket.remotePort.toUShort()


    private suspend fun readBuffer(size:UInt) :SocketDataRead<ReadBuffer> {
        var currentBuffer = currentBuffer ?: incomingMessageChannel.receive().result
        var bytesLeft = currentBuffer.remaining().toInt()
        while (bytesLeft > 0) {
            val socketDataResult = incomingMessageChannel.receive()
            bytesLeft -= socketDataResult.bytesRead
            currentBuffer = FragmentedReadBuffer(currentBuffer, socketDataResult.result).slice()
        }
        this.currentBuffer = currentBuffer
        return SocketDataRead(currentBuffer, size.toInt())
    }

    override suspend fun readBuffer(timeout: Duration): SocketDataRead<ReadBuffer> {
        val msg = incomingMessageChannel.receive()
        netSocket.resume()
        return msg.copy(result = msg.result.slice())
    }

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        val receivedData = readBuffer(buffer.remaining())
        netSocket.resume()
        val resultBuffer = receivedData.result
        resultBuffer.position(0)
        return receivedData.bytesRead
    }
    override suspend fun <T> read(timeout: Duration, bufferSize: UInt, bufferRead: (PlatformBuffer, Int) -> T): SocketDataRead<T> {
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

    override suspend fun awaitClose() {
        disconnectedFlow.asSharedFlow().first()
    }

    override suspend fun close() {
        println("nsc closing")
        isClosed = true
        disconnectedFlow.emit(Unit)
        println("nsc close emitted")
        try {
            incomingMessageChannel.close()
            println("nsc closed incoming channel")
        } catch (t: Throwable) {}
        try {
            netSocket.close()
            println("nsc closed net socket")
        } catch (t: Throwable) {}
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
            val buffer = allocateNewBuffer(4u*1024u) as JsBuffer
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
        val netSocket = connect(options)
        isClosed = false
        this.netSocket = netSocket
        netSocket.on("error") { err ->
            error(err.toString())
        }
        netSocket.on("close") { ->
            println("nsc net socket onclose")
            isClosed = true
            disconnectedFlow.tryEmit(Unit)
            incomingMessageChannel.close()
        }
        return SocketOptions()
    }
}
