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
    /**
     * Sends on [stream]. quiche answers Done both when it can take nothing now and when it already let the stream go:
     * a stream our side has not ended is let go only once the peer stopped it, so that answer is [STOPPED].
     */
    fun send(
        stream: QuicStreamId,
        address: Long,
        length: Int,
        end: WriteEnd,
    ): StreamSendResult {
        val sent = q.api.connStreamSend(q.handle, stream, address, length, end == WriteEnd.Finish)
        if (sent.result != QUICHE_ERR_DONE) return sent
        return when (capacity(stream)) {
            QUICHE_ERR_STREAM_STOPPED.toLong(), QUICHE_ERR_INVALID_STREAM_STATE.toLong() -> STOPPED
            else -> sent
        }
    }

    /**
     * What [stream] can take now, or a quiche error once it can take nothing more. Asking is also what lets quiche
     * let go of a stream the peer stopped once both directions are over.
     */
    fun capacity(stream: QuicStreamId): Long = q.api.connStreamCapacity(q.handle, stream)

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

    private companion object {
        /** A send refused because the peer stopped the stream; quiche no longer says with which code. */
        val STOPPED = StreamSendResult(QUICHE_ERR_STREAM_STOPPED)
    }
}
