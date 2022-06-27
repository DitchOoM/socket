package com.ditchoom.data

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Writer {
    suspend fun write(buffer: ReadBuffer, timeout: Duration = 1.seconds): Int
    suspend fun write(string: String, timeout: Duration = 1.seconds): Int {
        return write(string.toBuffer(), timeout)
    }
}