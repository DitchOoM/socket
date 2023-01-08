package com.ditchoom.data

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Reader {
    fun isOpen(): Boolean

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun read(timeout: Duration = 1.seconds): ReadBuffer

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun readString(charset: Charset, timeout: Duration = 1.seconds): CharSequence {
        val buffer = read(timeout)
        buffer.resetForRead()
        return buffer.readString(buffer.remaining(), charset)
    }
}
