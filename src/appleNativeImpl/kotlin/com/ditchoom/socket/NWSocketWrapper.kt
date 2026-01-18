package com.ditchoom.socket

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.DataBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import com.ditchoom.socket.native.SocketErrorType
import com.ditchoom.socket.native.SocketErrorTypeDns
import com.ditchoom.socket.native.SocketErrorTypeNone
import com.ditchoom.socket.native.SocketErrorTypePosix
import com.ditchoom.socket.native.SocketErrorTypeTls
import com.ditchoom.socket.native.SocketWrapper
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

    override fun isOpen(): Boolean = socket?.isOpen() ?: false

    override suspend fun localPort(): Int = socket?.localPort()?.toInt() ?: -1

    override suspend fun remotePort(): Int = socket?.remotePort()?.toInt() ?: -1

    /**
     * Zero-copy read operation.
     * Returns a buffer backed by NSData received from Network.framework.
     */
    override suspend fun read(timeout: Duration): ReadBuffer {
        val socket = socket ?: return EMPTY_BUFFER
        return readMutex.withLock {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    socket.readWithCompletion { data: NSData?, errorType, errorString, isComplete ->
                        when {
                            data != null && data.length.toInt() > 0 -> {
                                // Zero-copy: wrap NSData directly using DataBuffer
                                val buffer = DataBuffer(data, ByteOrder.BIG_ENDIAN)
                                // Set position to end of data so resetForRead() works correctly
                                // resetForRead() will set limit = position, then position = 0
                                buffer.position(data.length.toInt())
                                continuation.resume(buffer)
                            }
                            isComplete -> {
                                // Connection closed by peer - treat as EOF
                                closeInternal()
                                continuation.resumeWithException(
                                    SocketClosedException("Connection closed by peer"),
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
        val socket = socket ?: return -1
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
        val socket = socket ?: return
        if (!isOpen()) return
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
            return when (errorType) {
                SocketErrorTypeDns -> SocketUnknownHostException(null, message)
                SocketErrorTypeTls -> SSLSocketException(message)
                SocketErrorTypePosix -> SocketException(message)
                else -> SocketException(message)
            }
        }
    }
}
