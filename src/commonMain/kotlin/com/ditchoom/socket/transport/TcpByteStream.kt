package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration

class TcpByteStream(
    private val socket: ClientToServerSocket,
    private val context: ConnectionContext,
) : ByteStream {
    override val isOpen: Boolean get() = socket.isOpen()

    override suspend fun read(timeout: Duration): ReadResult {
        val buffer = context.pool.acquire(context.options.defaultBufferSize)
        return try {
            val bytesRead = socket.read(buffer, timeout)
            if (bytesRead <= 0) {
                buffer.freeIfNeeded()
                ReadResult.End
            } else {
                buffer.setLimit(buffer.position())
                buffer.position(0)
                ReadResult.Data(buffer)
            }
        } catch (_: SocketClosedException.ConnectionReset) {
            buffer.freeIfNeeded()
            ReadResult.Reset
        } catch (_: SocketClosedException) {
            buffer.freeIfNeeded()
            ReadResult.End
        } catch (e: Exception) {
            buffer.freeIfNeeded()
            throw e
        }
    }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten = BytesWritten(socket.write(buffer, timeout))

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): BytesWritten = BytesWritten(socket.writeGathered(buffers, timeout))

    override suspend fun close() {
        socket.close()
        context.close()
    }
}
