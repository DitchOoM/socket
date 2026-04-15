package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration

class TcpByteStream(
    private val socket: ClientToServerSocket,
) : ByteStream {
    override val isOpen: Boolean get() = socket.isOpen()

    override suspend fun read(timeout: Duration): ReadResult =
        try {
            val buffer = socket.read(timeout)
            if (buffer.remaining() <= 0) {
                buffer.freeIfNeeded()
                ReadResult.End
            } else {
                ReadResult.Data(buffer)
            }
        } catch (_: SocketClosedException.ConnectionReset) {
            ReadResult.Reset
        } catch (_: SocketClosedException) {
            ReadResult.End
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
    }
}
