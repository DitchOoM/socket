package com.ditchoom.socket

import cocoapods.SocketWrapper.SocketWrapper
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.DataBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
open class NWSocketWrapper : ClientSocket {
    internal var socket: SocketWrapper? = null
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    override fun isOpen(): Boolean = socket?.isOpen() ?: false

    override suspend fun localPort(): Int = socket?.localPort()?.toInt() ?: -1

    override suspend fun remotePort(): Int = socket?.remotePort()?.toInt() ?: -1
    override suspend fun read(timeout: Duration): ReadBuffer {
        val socket = socket ?: return PlatformBuffer.allocate(0)
        return readMutex.withLock {
            withTimeout(timeout) {
                suspendCancellableCoroutine {
                    socket.readDataWithCompletion { data, errorString, _ ->
                        if (errorString != null) {
                            closeInternal()
                            it.resumeWithException(SocketClosedException(errorString))
                        } else if (data != null) {
                            if (data.length.toInt() == 0) {
                                it.resume(PlatformBuffer.allocate(0))
                            } else {
                                val d = DataBuffer(data, ByteOrder.BIG_ENDIAN)
                                d.position(data.length.toInt())
                                it.resume(d)
                            }
                        } else {
                            it.resumeWithException(IllegalStateException("Failed to get a valid error message from reading on socket"))
                        }
                    }
                    it.invokeOnCancellation {
                        closeInternal()
                    }
                }
            }
        }
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        val socket = socket ?: return -1
        val readBuffer = buffer as DataBuffer
        return writeMutex.withLock {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    socket.writeDataWithBuffer(readBuffer.data) { bytesWritten, errorString ->
                        if (errorString != null) {
                            continuation.resumeWithException(SocketException(errorString))
                        } else {
                            continuation.resume(bytesWritten.toInt())
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
        socket.cancel()
        socket.forceCancel()
    }

    override suspend fun close() {
        closeInternal()
    }
}
