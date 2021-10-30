@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.allocateNewBuffer
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
internal class SocketFlowReader(
    private val socket: ClientSocket,
    private val timeout: Duration,
    private val bufferSize :UInt = 8096u,
): SuspendCloseable {

    suspend fun read() = flow<ReadBuffer> {
        while (socket.isOpen()) {
            val buffer = allocateNewBuffer(bufferSize)
            try {
                val bytesRead = socket.read(buffer, timeout)
                if (bytesRead < 0) {
                    return@flow
                }
                emit(buffer)
            } catch (e: Exception) {
            }
        }
    }

    override suspend fun close() {
        socket.close()
    }
}