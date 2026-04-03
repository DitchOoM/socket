package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface ByteStream {
    val isOpen: Boolean

    suspend fun read(timeout: Duration = 15.seconds): ReadResult

    @Throws(CancellationException::class, SocketException::class)
    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = 15.seconds,
    ): BytesWritten

    @Throws(CancellationException::class, SocketException::class)
    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration = 15.seconds,
    ): BytesWritten {
        var total = 0
        for (buf in buffers) total += write(buf, timeout).count
        return BytesWritten(total)
    }

    suspend fun close()
}
