package com.ditchoom.data

import kotlin.time.Duration

interface Writer<T> {
    suspend fun write(buffer: T, timeout: Duration): Int
}