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
    suspend fun write(buffer: ReadBuffer, timeout: Duration = 1.seconds): Int

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun write(string: String, charset: Charset, timeout: Duration = 1.seconds): Int {
        return write(string.toReadBuffer(charset), timeout)
    }
}
