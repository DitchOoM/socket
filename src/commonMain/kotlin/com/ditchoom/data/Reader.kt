package com.ditchoom.data

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface Reader<T> {
    fun isOpen(): Boolean
    suspend fun readData(timeout: Duration): T
}