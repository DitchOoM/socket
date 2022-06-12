package com.ditchoom.socket.nio

import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SocketException
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.aLocalAddress
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.nio.channels.NetworkChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.ExperimentalTime

abstract class ByteBufferClientSocket<T : NetworkChannel> : ClientSocket {
    protected lateinit var socket: T
    protected val isClosing = AtomicBoolean(false)
    protected var closeInitiatedClientSide = false
    protected val disconnectedFlow = MutableSharedFlow<SocketException>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun isOpen() = try {
        socket.isOpen && !isClosing.get()
    } catch (e: Throwable) {
        false
    }

    override suspend fun localPort(): UShort? = (socket.aLocalAddress() as? InetSocketAddress)?.port?.toUShort()

    override suspend fun awaitClose() = disconnectedFlow.asSharedFlow().first()

    override suspend fun close() {
        isClosing.set(true)
        closeInitiatedClientSide = true
        socket.aClose()
        disconnectedFlow.emit(SocketException("User closed socket", wasInitiatedClientSide = closeInitiatedClientSide))
    }
}
