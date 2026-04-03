package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration

class TcpByteStream(
    private val socket: ClientToServerSocket,
) : ByteStream {
    override val isOpen: Boolean get() = socket.isOpen()

    override suspend fun read(timeout: Duration): ReadResult =
        try {
            ReadResult.Data(socket.read(timeout))
        } catch (_: SocketClosedException.EndOfStream) {
            ReadResult.End
        } catch (_: SocketClosedException.ConnectionReset) {
            ReadResult.Reset
        }

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): BytesWritten = BytesWritten(socket.write(buffer, timeout))

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): BytesWritten = BytesWritten(socket.writeGathered(buffers, timeout))

    override suspend fun close() = socket.close()
}
