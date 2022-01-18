package com.ditchoom.socket

import com.ditchoom.buffer.ParcelablePlatformBuffer
import com.ditchoom.buffer.allocateNewBuffer
import com.ditchoom.buffer.toBuffer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalUnsignedTypes
@ExperimentalTime
class MockClientSocket : ClientToServerSocket {
    var isOpenInternal = false
    private val incomingQueue = Channel<ParcelablePlatformBuffer>()
    var localPortInternal: UShort? = null
    var remotePortInternal: UShort? = null
    val disconnectedFlow = MutableSharedFlow<SocketException>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    lateinit var remote: MockClientSocket

    override suspend fun open(
        port: UShort,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        localPortInternal = lastFakePortUsed++.toUShort()
        remotePortInternal = port
        MockServerSocket.clientsToAccept.send(this)
        isOpenInternal = true
        return socketOptions ?: SocketOptions()
    }

    override fun isOpen() = isOpenInternal

    override suspend fun localPort() = localPortInternal

    override suspend fun remotePort() = remotePortInternal

    override suspend fun read(buffer: ParcelablePlatformBuffer, timeout: Duration): Int {
        val incoming = incomingQueue.receive()
        incoming.resetForRead()
        buffer.write(incoming)
        buffer.position(0)
        buffer.setLimit(incoming.limit().toInt())
        return buffer.limit().toInt()
    }

    override suspend fun <T> read(
        timeout: Duration,
        bufferSize: UInt,
        bufferRead: (ParcelablePlatformBuffer, Int) -> T
    ): SocketDataRead<T> {
        val buffer = allocateNewBuffer(bufferSize)
        buffer.setLimit(bufferSize.toInt())
        val bytesRead = read(buffer, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    override suspend fun read(timeout: Duration) = read(timeout) { buffer, bytesRead ->
        buffer.readUtf8(bytesRead)
    }

    override suspend fun write(buffer: ParcelablePlatformBuffer, timeout: Duration): Int {
        remote.incomingQueue.send(buffer)
        return buffer.limit().toInt()
    }

    override suspend fun write(buffer: String, timeout: Duration): Int =
        write(buffer.toBuffer().also { it.position(it.limit().toInt()) }, timeout)

    override suspend fun awaitClose() = disconnectedFlow.asSharedFlow().first()

    override suspend fun close() {
        disconnectedFlow.emit(SocketException("Mock close", true))
    }

    companion object {
        var lastFakePortUsed = 1
    }
}