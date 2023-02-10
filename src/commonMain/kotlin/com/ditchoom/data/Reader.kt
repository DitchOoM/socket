package com.ditchoom.data

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface Reader {
    fun isOpen(): Boolean

    fun readFlow(timeout: Duration = 15.seconds): Flow<ReadBuffer> = flow {
        while (isOpen()) {
            try {
                emit(read(timeout))
            } catch (e: SocketClosedException) {
                return@flow
            }
        }
    }

    fun readFlowString(charset: Charset = Charset.UTF8, timeout: Duration = 15.seconds) = readFlow(timeout).map {
        it.resetForRead()
        it.readString(it.remaining(), charset)
    }

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun read(timeout: Duration = 15.seconds): ReadBuffer

    @Throws(CancellationException::class, SocketClosedException::class)
    suspend fun readString(charset: Charset = Charset.UTF8, timeout: Duration = 15.seconds): String {
        val buffer = read(timeout)
        buffer.resetForRead()
        return buffer.readString(buffer.remaining(), charset)
    }
}
