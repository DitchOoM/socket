package com.ditchoom.socket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.nwhelpers.SocketErrorTypeDns
import com.ditchoom.socket.nwhelpers.SocketErrorTypePosix
import com.ditchoom.socket.nwhelpers.SocketErrorTypeTls
import com.ditchoom.socket.nwhelpers.nw_helper_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_force_cancel
import com.ditchoom.socket.nwhelpers.nw_helper_local_port
import com.ditchoom.socket.nwhelpers.nw_helper_remote_port
import com.ditchoom.socket.nwhelpers.nw_helper_send_tcp
import com.ditchoom.socket.nwhelpers.nw_helper_tcp_receive
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.Network.nw_connection_t
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/**
 * Base socket wrapper using Apple's Network.framework with zero-copy buffer integration.
 *
 * Read operations return [ReadBuffer] backed by NSData without copying data.
 * Write operations pass NSData directly to Network.framework without copying.
 */
@OptIn(ExperimentalForeignApi::class)
open class NWSocketWrapper : ClientSocket {
    internal var connection: nw_connection_t = null
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    internal var closedLocally = false

    @Volatile
    internal var connectionReady = false

    /** Injected-once configuration; set at the top of [NWClientSocketWrapper.open]. */
    internal var config: TransportConfig = TransportConfig()

    override val readPolicy: ReadPolicy get() = config.readPolicy
    override val writePolicy: WritePolicy get() = config.writePolicy

    override val isOpen: Boolean get() = !closedLocally && connectionReady

    override suspend fun localPort(): Int =
        connection?.let {
            nw_helper_local_port(it).toInt()
        } ?: -1

    override suspend fun remotePort(): Int =
        connection?.let {
            nw_helper_remote_port(it).toInt()
        } ?: -1

    override suspend fun read(deadline: Duration): ReadResult = translateRead { readRaw(deadline) }

    /**
     * Zero-copy read operation.
     * Returns a buffer backed by NSData received from Network.framework.
     */
    private suspend fun readRaw(deadline: Duration): ReadBuffer {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val conn = connection ?: throw SocketClosedException.General("Socket is closed")
        return readMutex.withLock {
            withTimeout(deadline) {
                suspendCancellableCoroutine { continuation ->
                    nw_helper_tcp_receive(conn, 1u, 65536u) { data, isComplete, errorDomain, _, errorDesc ->
                        when {
                            data != null && data.length.toInt() > 0 -> {
                                // Zero-copy: wrap NSData directly using NSDataBuffer
                                val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                                buffer.position(data.length.toInt())
                                buffer.resetForRead()
                                continuation.resume(buffer)
                            }
                            isComplete?.boolValue == true -> {
                                closeInternal()
                                continuation.resumeWithException(
                                    SocketClosedException.EndOfStream(),
                                )
                            }
                            errorDomain != 0 -> {
                                closeInternal()
                                continuation.resumeWithException(
                                    mapSocketException(errorDomain, errorDesc),
                                )
                            }
                            else -> {
                                continuation.resume(EMPTY_BUFFER)
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        closeInternal()
                    }
                }
            }
        }
    }

    /**
     * Zero-copy write operation.
     * Passes NSData directly to Network.framework without copying.
     *
     * Advances [buffer]'s position by the number of bytes accepted, matching the JVM NIO
     * contract (`socket.write(byteBuffer)` moves position). [ReadBuffer.toNSData] always
     * produces an NSData whose length equals `buffer.remaining()` at call time.
     */
    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val conn = connection ?: throw SocketClosedException.General("Socket is closed")
        val nsData = buffer.toNSData()
        val bytesToWrite = nsData.length.toInt()

        return BytesWritten(
            writeMutex.withLock {
                withTimeout(deadline) {
                    suspendCancellableCoroutine { continuation ->
                        nw_helper_send_tcp(conn, nsData) { errorDomain, _, errorDesc ->
                            if (errorDomain != 0) {
                                continuation.resumeWithException(
                                    mapSocketException(errorDomain, errorDesc),
                                )
                            } else {
                                buffer.position(buffer.position() + bytesToWrite)
                                continuation.resume(bytesToWrite)
                            }
                        }
                        continuation.invokeOnCancellation {
                            closeInternal()
                        }
                    }
                }
            },
        )
    }

    internal fun closeInternal() {
        if (closedLocally) return
        closedLocally = true
        connectionReady = false
        val conn = connection ?: return
        nw_helper_cancel(conn)
        nw_helper_force_cancel(conn)
    }

    override suspend fun close() {
        closeInternal()
    }

    companion object {
        fun mapSocketException(
            errorDomain: Int,
            errorString: String?,
            hostname: String? = null,
        ): SocketException {
            val message = errorString ?: "Socket error"
            val msgLower = message.lowercase()
            return when (errorDomain) {
                SocketErrorTypeDns -> SocketUnknownHostException(hostname, message)
                SocketErrorTypeTls -> {
                    if (msgLower.contains("handshake") ||
                        msgLower.contains("certificate") ||
                        msgLower.contains("cert") ||
                        msgLower.contains("trust")
                    ) {
                        SSLHandshakeFailedException(message)
                    } else {
                        SSLProtocolException(message)
                    }
                }
                SocketErrorTypePosix -> {
                    when {
                        // DNS failures can arrive as POSIX errors on macOS
                        // (e.g., EAI_NONAME = "nodename nor servname provided")
                        msgLower.contains("nodename") ||
                            msgLower.contains("servname") ||
                            msgLower.contains("name or service not known") ||
                            msgLower.contains("host not found") ->
                            SocketUnknownHostException(hostname, message)
                        msgLower.contains("connection refused") || msgLower.contains("econnrefused") ->
                            SocketConnectionException.Refused(null, 0, platformError = message)
                        msgLower.contains("timed out") || msgLower.contains("timeout") ->
                            SocketTimeoutException(message)
                        msgLower.contains("reset") || msgLower.contains("connection abort") ->
                            SocketClosedException.ConnectionReset(message)
                        msgLower.contains("broken pipe") ->
                            SocketClosedException.BrokenPipe(message)
                        msgLower.contains("not connected") || msgLower.contains("socket is not connected") ->
                            SocketClosedException.BrokenPipe(message)
                        msgLower.contains("network") && msgLower.contains("unreachable") ->
                            SocketConnectionException.NetworkUnreachable(message)
                        msgLower.contains("host") && msgLower.contains("unreachable") ->
                            SocketConnectionException.HostUnreachable(message)
                        msgLower.contains("unreachable") ->
                            SocketConnectionException.NetworkUnreachable(message)
                        else -> SocketIOException(message)
                    }
                }
                else -> SocketIOException(message)
            }
        }
    }
}
