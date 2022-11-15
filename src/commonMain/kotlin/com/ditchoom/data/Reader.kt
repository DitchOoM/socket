package com.ditchoom.data

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Reader {
    fun isOpen(): Boolean

    @Throws(SocketClosedException::class)
    suspend fun read(timeout: Duration = 1.seconds): ReadBuffer

    @Throws(SocketClosedException::class)
    suspend fun readUtf8(timeout: Duration = 1.seconds): CharSequence {
        val buffer = read(timeout)
        buffer.resetForRead()
        return buffer.readUtf8(buffer.remaining())
    }
}
