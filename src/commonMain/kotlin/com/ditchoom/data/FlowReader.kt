package com.ditchoom.data

import com.ditchoom.buffer.SuspendCloseable
import kotlinx.coroutines.flow.Flow
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface FlowReader<T> : SuspendCloseable {
    fun read(): Flow<T>
}