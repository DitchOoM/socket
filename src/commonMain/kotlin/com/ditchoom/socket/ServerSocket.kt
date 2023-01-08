package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocate
import kotlinx.coroutines.CoroutineScope

interface ServerSocket : SuspendCloseable {
    val allocationZone: AllocationZone
        get() = AllocationZone.Direct

    fun setScope(scope: CoroutineScope) {}

    suspend fun start(
        port: Int = -1,
        host: String? = null,
        socketOptions: SocketOptions? = null,
        backlog: Int = 0,
        acceptedClient: suspend (ClientSocket) -> Unit
    ): SocketOptions

    fun isOpen(): Boolean
    fun port(): Int

    companion object
}

expect fun ServerSocket.Companion.allocate(
    bufferFactory: () -> PlatformBuffer = {
        PlatformBuffer.allocate(4 * 1024, AllocationZone.Direct)
    }
): ServerSocket
