package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate

interface ServerSocket : SuspendCloseable {
    val allocationZone: AllocationZone
        get() = AllocationZone.Direct

    suspend fun bind(
        port: Int = -1,
        host: String? = null,
        socketOptions: SocketOptions? = null,
        backlog: Int = 0
    ): SocketOptions

    suspend fun accept(): ClientSocket
    fun isOpen(): Boolean
    fun port(): Int

    companion object
}

expect fun ServerSocket.Companion.allocate(
    bufferFactory: () -> PlatformBuffer = {
        PlatformBuffer.allocate(4 * 1024, AllocationZone.Direct)
    }
): ServerSocket