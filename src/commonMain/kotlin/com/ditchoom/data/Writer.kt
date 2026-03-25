package com.ditchoom.data

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Writer {
    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration = 15.seconds,
    ): Int

    /**
     * Writes multiple buffers sequentially. Platforms may override with true scatter-gather
     * (e.g., GatheringByteChannel on JVM NIO, writev on Linux).
     */
    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration = 15.seconds,
    ): Int {
        var total = 0
        for (buf in buffers) total += write(buf, timeout)
        return total
    }

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun writeString(
        string: String,
        charset: Charset = Charset.UTF8,
        timeout: Duration = 15.seconds,
    ): Int = write(string.toReadBuffer(charset), timeout)
}
