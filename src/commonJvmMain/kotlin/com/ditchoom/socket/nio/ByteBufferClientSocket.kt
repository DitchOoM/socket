package com.ditchoom.socket.nio

import com.ditchoom.buffer.BaseJvmBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.IoTuning
import com.ditchoom.socket.JvmTlsHandler
import com.ditchoom.socket.ReadBufferSource
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.nio.util.aClose
import com.ditchoom.socket.nio.util.localAddressOrNull
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.channels.NetworkChannel
import kotlin.time.Duration

abstract class ByteBufferClientSocket<T : NetworkChannel>(
    /** The injected-once configuration tree, supplied at `allocate(config)` / accept time. */
    protected val config: TransportConfig = TransportConfig(),
) : ClientSocket {
    protected lateinit var socket: T
    internal var tlsHandler: JvmTlsHandler? = null

    /**
     * The single per-connection source of receive buffers — a pool over [config]'s buffer factory, so
     * `readRaw` reuses buffers instead of allocating a fresh GC-reclaimed one per read (see
     * [ReadBufferSource]).
     */
    protected val readBufferSource: ReadBufferSource by lazy { ReadBufferSource(config) }

    override val readPolicy: ReadPolicy get() = config.readPolicy
    override val writePolicy: WritePolicy get() = config.writePolicy

    protected val isSocketInitialized: Boolean
        get() = ::socket.isInitialized

    override val isOpen: Boolean
        get() =
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
                bufferFactory = this.config.bufferFactory,
            )
        handler.handshake(timeout)
        this.tlsHandler = handler
    }

    /**
     * Effective receive-buffer sizing, mirroring the Linux path's `getEffectiveReadBufferSize`:
     * an explicitly-configured [IoTuning.readBufferSize] (any value other than the default) wins;
     * otherwise fall back to the socket's SO_RCVBUF ([soRcvBuf]).
     *
     * Without this the JVM NIO/NIO2 read path always sized the pooled receive buffer to SO_RCVBUF
     * (~512 KiB on some emulators) and silently ignored the [IoTuning.readBufferSize] override,
     * contradicting its own contract and pinning oversized pool buffers under sustained reads.
     */
    protected fun effectiveReadBufferSize(soRcvBuf: Int): Int {
        val configured = config.io.readBufferSize
        return if (configured != DEFAULT_READ_BUFFER_SIZE) configured else soRcvBuf
    }

    protected fun applySocketOptions(options: IoTuning) {
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

    private companion object {
        /**
         * Sentinel matching [IoTuning.readBufferSize]'s default (and linuxMain's
         * `DEFAULT_READ_BUFFER_SIZE`). Any other value means the caller explicitly
         * overrode receive-buffer sizing, so it takes precedence over SO_RCVBUF.
         */
        const val DEFAULT_READ_BUFFER_SIZE = 65536
    }
}
