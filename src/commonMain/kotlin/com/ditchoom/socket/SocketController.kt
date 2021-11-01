@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface SocketController: SuspendCloseable {
    fun isOpen(): Boolean

    suspend fun writeFully(buffer: PlatformBuffer, timeout: Duration = seconds(1))
    suspend fun readBuffer(timeout: Duration = seconds(1)): SocketDataRead<ReadBuffer>

    suspend fun readFlow(timeout: Duration = seconds(1)) = flow {
        while (isOpen()) {
            emit(readBuffer(timeout).result)
        }
    }
}