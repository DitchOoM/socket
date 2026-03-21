package com.ditchoom.socket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.NSDataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.socket.native.SocketErrorType
import com.ditchoom.socket.native.SocketErrorTypeDns
import com.ditchoom.socket.native.SocketErrorTypeNone
import com.ditchoom.socket.native.SocketErrorTypePosix
import com.ditchoom.socket.native.SocketErrorTypeTls
import com.ditchoom.socket.native.SocketWrapper
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
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
    internal var socket: SocketWrapper? = null
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    internal var closedLocally = false

    override fun applyOptions(options: SocketOptions) {
        // Network.framework NWConnection doesn't expose the underlying fd,
        // so SO_LINGER cannot be set. Other options (TCP_NODELAY, etc.) are
        // configured via NWParameters at connection creation time.
    }

    override fun isOpen(): Boolean = !closedLocally && (socket?.isOpen() ?: false)

    override suspend fun localPort(): Int = socket?.localPort()?.toInt() ?: -1

    override suspend fun remotePort(): Int = socket?.remotePort()?.toInt() ?: -1

    /**
     * Zero-copy read operation.
     * Returns a buffer backed by NSData received from Network.framework.
     */
    override suspend fun read(timeout: Duration): ReadBuffer {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val socket = socket ?: throw SocketClosedException.General("Socket is closed")
        return readMutex.withLock {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    socket.readWithCompletion { data: NSData?, errorType, errorString, isComplete ->
                        when {
                            data != null && data.length.toInt() > 0 -> {
                                // Zero-copy: wrap NSData directly using NSDataBuffer
                                val buffer = NSDataBuffer(data, ByteOrder.BIG_ENDIAN)
                                // Set position to end of data so resetForRead() works correctly
                                // resetForRead() will set limit = position, then position = 0
                                buffer.position(data.length.toInt())
                                continuation.resume(buffer)
                            }
                            isComplete -> {
                                // Connection closed by peer - treat as EOF
                                closeInternal()
                                continuation.resumeWithException(
                                    SocketClosedException.EndOfStream(),
                                )
                            }
                            errorType != SocketErrorTypeNone -> {
                                // Only throw error if not a clean close
                                closeInternal()
                                continuation.resumeWithException(
                                    mapSocketException(errorType, errorString),
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
     */
    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        if (closedLocally) throw SocketClosedException.General("Socket is closed")
        val socket = socket ?: throw SocketClosedException.General("Socket is closed")
        val nsData = buffer.toNSData()

        return writeMutex.withLock {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    socket.writeWithData(nsData) { bytesWritten, errorType, errorString ->
                        when {
                            errorType != SocketErrorTypeNone -> {
                                continuation.resumeWithException(
                                    mapSocketException(errorType, errorString),
                                )
                            }
                            else -> {
                                continuation.resume(bytesWritten.toInt())
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

    private fun closeInternal() {
        if (closedLocally) return
        closedLocally = true
        val socket = socket ?: return
        socket.close()
        socket.forceClose()
    }

    override suspend fun close() {
        closeInternal()
    }

    companion object {
        internal fun mapSocketException(
            errorType: SocketErrorType,
            errorString: String?,
        ): SocketException {
            val message = errorString ?: "Socket error"
            val msgLower = message.lowercase()
            return when (errorType) {
                SocketErrorTypeDns -> SocketUnknownHostException(null, message)
                SocketErrorTypeTls -> {
                    if (msgLower.contains("handshake") || msgLower.contains("certificate") ||
                        msgLower.contains("cert") || msgLower.contains("trust")
                    ) {
                        SSLHandshakeFailedException(message)
                    } else {
                        SSLProtocolException(message)
                    }
                }
                SocketErrorTypePosix -> {
                    when {
                        msgLower.contains("connection refused") || msgLower.contains("econnrefused") ->
                            SocketConnectionException.Refused(null, 0, platformError = message)
                        msgLower.contains("timed out") || msgLower.contains("timeout") ->
                            SocketTimeoutException(message)
                        msgLower.contains("reset") ->
                            SocketClosedException.ConnectionReset(message)
                        msgLower.contains("broken pipe") ->
                            SocketClosedException.BrokenPipe(message)
                        msgLower.contains("not connected") ->
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
