package com.ditchoom.data

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface Writer<T> {
    suspend fun write(buffer: T, timeout: Duration): Int
}