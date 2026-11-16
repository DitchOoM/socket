package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamRecvStep
import com.ditchoom.socket.quic.StreamSendResult
import com.ditchoom.socket.quic.core.life.QuicheConnRef

/** Whether a write ends our side of the stream. */
internal enum class WriteEnd { More, Finish }

/** Which half a shutdown stops: the peer's sending (STOP_SENDING) or ours (RESET_STREAM). */
internal enum class ShutdownSide { Read, Write }

/** A stream's bytes: the only file that sends, receives or shuts down stream data in quiche. */
internal class StreamIo(
    private val q: QuicheConnRef,
) {
    fun send(
        stream: QuicStreamId,
        address: Long,
        length: Int,
        end: WriteEnd,
    ): StreamSendResult = q.api.connStreamSend(q.handle, stream, address, length, end == WriteEnd.Finish)

    fun recv(
        stream: QuicStreamId,
        address: Long,
        length: Int,
    ): StreamRecvStep = q.api.connStreamRecvStep(q.handle, stream, address, length)

    fun shutdown(
        stream: QuicStreamId,
        side: ShutdownSide,
        code: Long,
    ): Int =
        q.api.connStreamShutdown(
            q.handle,
            stream,
            when (side) {
                ShutdownSide.Read -> 0
                ShutdownSide.Write -> 1
            },
            code,
        )
}
