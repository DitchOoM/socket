package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.localAddressOrNull
import java.net.InetSocketAddress
import java.nio.channels.NetworkChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

abstract class ByteBufferClientSocket<T : NetworkChannel> : ClientSocket {
    protected lateinit var socket: T
    private val closed = AtomicBoolean(false)

    protected val isSocketInitialized: Boolean
        get() = ::socket.isInitialized

    val isClosed: Boolean
        get() = closed.get()

    override fun isOpen() =
        try {
            isSocketInitialized && socket.isOpen && !isClosed
        } catch (e: Throwable) {
            false
        }

    override suspend fun localPort(): Int = (socket.localAddressOrNull() as? InetSocketAddress)?.port ?: -1

    abstract suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            if (isSocketInitialized) {
                socket.aClose()
            }
        }
    }
}
