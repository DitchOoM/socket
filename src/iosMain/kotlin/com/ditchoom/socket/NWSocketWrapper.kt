package com.ditchoom.socket

import cocoapods.SocketWrapper.SocketWrapper
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.DataBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration

open class NWSocketWrapper : ClientSocket {
    internal var socket: SocketWrapper? = null

    override fun isOpen(): Boolean = socket?.isOpen() ?: false

    override suspend fun localPort(): Int = socket?.localPort()?.toInt() ?: -1

    override suspend fun remotePort(): Int = socket?.remotePort()?.toInt() ?: -1
    override suspend fun read(timeout: Duration): ReadBuffer {
        val socket = socket ?: return PlatformBuffer.allocate(0)
        return withTimeout(timeout) {
            suspendCancellableCoroutine {
                socket.readDataWithCompletion { data, errorString, isComplete ->
                    if (errorString != null) {
                        socket.closeWithCompletionHandler {
                            it.resumeWithException(SocketClosedException(errorString))
                        }
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
                    socket.closeWithCompletionHandler { }
                }
            }
        }
    }

    override suspend fun write(buffer: ReadBuffer, timeout: Duration): Int {
        val socket = socket ?: return -1
        val readBuffer = buffer as DataBuffer
        return withTimeout(timeout) {
            suspendCancellableCoroutine { continuation ->
                socket.writeDataWithBuffer(readBuffer.data) { bytesWritten, errorString ->
                    if (errorString != null) {
                        continuation.resumeWithException(SocketException(errorString))
                    } else {
                        continuation.resume(bytesWritten.toInt())
                    }
                }
                continuation.invokeOnCancellation {
                    socket.closeWithCompletionHandler { }
                }
            }
        }
    }

    override suspend fun close() {
        val socket = socket ?: return
        if (!isOpen()) return
        suspendCoroutine {
            socket.closeWithCompletionHandler {
                it.resume(Unit)
            }
        }
    }
}
