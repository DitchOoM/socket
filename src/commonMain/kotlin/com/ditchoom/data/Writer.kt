package com.ditchoom.data

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toBuffer
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Writer {
    @Throws(SocketClosedException::class)
    suspend fun write(buffer: ReadBuffer, timeout: Duration = 1.seconds): Int

    @Throws(SocketClosedException::class)
    suspend fun write(string: String, timeout: Duration = 1.seconds): Int {
        return write(string.toBuffer(), timeout)
    }
}