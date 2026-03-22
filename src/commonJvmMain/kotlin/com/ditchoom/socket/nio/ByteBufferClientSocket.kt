package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.JvmTlsHandler
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.localAddressOrNull
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.NetworkChannel
import kotlin.time.Duration

abstract class ByteBufferClientSocket<T : NetworkChannel> : ClientSocket {
    protected lateinit var socket: T
    internal var tlsHandler: JvmTlsHandler? = null

    protected val isSocketInitialized: Boolean
        get() = ::socket.isInitialized

    override fun isOpen() =
        try {
            isSocketInitialized && socket.isOpen
        } catch (e: Throwable) {
            false
        }

    override suspend fun localPort(): Int = (socket.localAddressOrNull() as? InetSocketAddress)?.port ?: -1

    abstract suspend fun read(
        buffer: BaseJvmBuffer,
        timeout: Duration,
    ): Int

    /**
     * Raw socket write bypassing TLS handler dispatch.
     * Used by JvmTlsHandler to send encrypted data directly to the socket.
     */
    internal abstract suspend fun rawSocketWrite(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int

    protected suspend fun initTls(
        hostname: String?,
        port: Int,
        config: TlsConfig,
        timeout: Duration,
    ) {
        val handler =
            JvmTlsHandler(
                config = config,
                hostname = hostname,
                port = port,
                rawRead = { buf, t -> this.read(buf, t) },
                rawWrite = { buf, t -> this.rawSocketWrite(buf, t) },
            )
        handler.handshake(timeout)
        this.tlsHandler = handler
    }

    protected fun applySocketOptions(options: SocketOptions) {
        options.tcpNoDelay?.let { socket.setOption(StandardSocketOptions.TCP_NODELAY, it) }
        options.reuseAddress?.let { socket.setOption(StandardSocketOptions.SO_REUSEADDR, it) }
        options.keepAlive?.let { socket.setOption(StandardSocketOptions.SO_KEEPALIVE, it) }
        options.receiveBuffer?.let { socket.setOption(StandardSocketOptions.SO_RCVBUF, it) }
        options.sendBuffer?.let { socket.setOption(StandardSocketOptions.SO_SNDBUF, it) }
    }

    override suspend fun close() {
        tlsHandler?.let {
            try {
                it.closeOutbound()
            } catch (_: Throwable) {
            }
        }
        if (isSocketInitialized) {
            socket.aClose()
        }
    }
}
