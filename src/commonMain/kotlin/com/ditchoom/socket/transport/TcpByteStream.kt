package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.ClientToServerSocket
import com.ditchoom.socket.ConnectionContext
import com.ditchoom.socket.SocketClosedException
import kotlin.time.Duration

class TcpByteStream(
    private val socket: ClientToServerSocket,
    private val context: ConnectionContext? = null,
) : ByteStream {
    override val isOpen: Boolean get() = socket.isOpen()

    override suspend fun read(timeout: Duration): ReadResult =
        try {
            ReadResult.Data(socket.read(timeout))
        } catch (_: SocketClosedException.ConnectionReset) {
            ReadResult.Reset
        } catch (_: SocketClosedException) {
            // EndOfStream, General, BrokenPipe — all mean connection is gone
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
        context?.close()
    }
}
