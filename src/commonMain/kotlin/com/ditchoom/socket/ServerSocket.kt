package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.flow.Flow

interface ServerSocket : SuspendCloseable {
    suspend fun bind(port: Int = -1, host: String? = null, backlog: Int = 0): Flow<ClientSocket>
    fun isListening(): Boolean
    fun port(): Int

    companion object
}

expect fun ServerSocket.Companion.allocate(
    bufferFactory: () -> PlatformBuffer = {
        PlatformBuffer.allocate(4 * 1024, AllocationZone.Direct)
    }
): ServerSocket
