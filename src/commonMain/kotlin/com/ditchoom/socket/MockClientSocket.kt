package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toBuffer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

class MockClientSocket : ClientToServerSocket {
    var isOpenInternal = false
    private val incomingQueue = Channel<PlatformBuffer>()
    var localPortInternal: Int = -1
    var remotePortInternal: Int = -1
    val disconnectedFlow = MutableSharedFlow<SocketException>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    lateinit var remote: MockClientSocket

    override suspend fun open(
        port: Int,
        timeout: Duration,
        hostname: String?,
        socketOptions: SocketOptions?
    ): SocketOptions {
        localPortInternal = lastFakePortUsed++
        remotePortInternal = port
        MockServerSocket.clientsToAccept.send(this)
        isOpenInternal = true
        return socketOptions ?: SocketOptions()
    }

    override fun isOpen() = isOpenInternal

    override suspend fun localPort() = localPortInternal

    override suspend fun remotePort() = remotePortInternal

    override suspend fun read(buffer: PlatformBuffer, timeout: Duration): Int {
        val incoming = incomingQueue.receive()
        incoming.resetForRead()
        buffer.write(incoming)
        buffer.position(0)
        buffer.setLimit(incoming.limit())
        return buffer.limit()
    }

    override suspend fun <T> read(
        timeout: Duration,
        bufferSize: Int,
        bufferRead: (PlatformBuffer, Int) -> T
    ): SocketDataRead<T> {
        val buffer = PlatformBuffer.allocate(bufferSize)
        buffer.setLimit(bufferSize)
        val bytesRead = read(buffer, timeout)
        return SocketDataRead(bufferRead(buffer, bytesRead), bytesRead)
    }

    override suspend fun read(timeout: Duration) = read(timeout) { buffer, bytesRead ->
        buffer.readUtf8(bytesRead)
    }

    override suspend fun write(buffer: PlatformBuffer, timeout: Duration): Int {
        remote.incomingQueue.send(buffer)
        return buffer.limit()
    }

    override suspend fun write(buffer: String, timeout: Duration): Int =
        write(buffer.toBuffer().also { it.position(it.limit()) }, timeout)

    override suspend fun awaitClose() = disconnectedFlow.asSharedFlow().first()

    override suspend fun close() {
        disconnectedFlow.emit(SocketException("Mock close", true))
    }

    companion object {
        var lastFakePortUsed = 1
    }
}