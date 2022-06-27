package com.ditchoom.data

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration

interface Writer {
    suspend fun write(buffer: ReadBuffer, timeout: Duration): Int
}