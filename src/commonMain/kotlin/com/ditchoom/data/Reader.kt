package com.ditchoom.data

import com.ditchoom.buffer.ReadBuffer
import kotlin.time.Duration

interface Reader {
    fun isOpen(): Boolean
    suspend fun read(timeout: Duration): ReadBuffer
}