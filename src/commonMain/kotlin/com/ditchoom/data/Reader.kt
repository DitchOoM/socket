package com.ditchoom.data

import kotlin.time.Duration

interface Reader<T> {
    fun isOpen(): Boolean
    suspend fun readData(timeout: Duration): T
}