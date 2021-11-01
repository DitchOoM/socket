@file:Suppress("EXPERIMENTAL_API_USAGE")

package com.ditchoom.socket

import com.ditchoom.buffer.SuspendCloseable
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
internal class BufferedReader(
    private val socket: SocketController,
    private val timeout: Duration,
): SuspendCloseable {

    fun read() = flow {
        while (socket.isOpen()) {
            try {
                val socketDataReadTmp = socket.readBuffer(timeout)
                var newBuffer = socketDataReadTmp.result.slice()
                newBuffer.setLimit(socketDataReadTmp.bytesRead)
                newBuffer = newBuffer.slice()
                val socketDataRead = socketDataReadTmp.copy(newBuffer)
                if (socketDataRead.bytesRead < 0) {
                    return@flow
                }
                emit(socketDataRead.result)
            } catch (e: Exception) {
            }
        }
    }

    override suspend fun close() {
        socket.close()
    }
}