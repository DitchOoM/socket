package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamRecvStep
import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined

/** Whether a write ends our side of the stream. */
internal enum class WriteEnd { More, Finish }

/** Which half a shutdown stops: the peer's sending (STOP_SENDING) or ours (RESET_STREAM). */
internal enum class ShutdownSide { Read, Write }

/** A stream's bytes: the only file that sends, receives or shuts down stream data in quiche. */
@Confined
internal class StreamIo(
    private val q: QuicheConnRef,
) {
    /** Monotonic: sends that gave quiche bytes or a FIN, and shutdowns, each something the peer will be sent. */
    var output = 0L
        private set

    /**
     * Sends on [stream], allocating nothing. quiche answers Done both when it can take nothing now and when it already
     * let the stream go: a stream our side has not ended is let go only once the peer stopped it, so that answer is
     * [STOPPED].
     */
    fun send(
        stream: QuicStreamId,
        address: Long,
        length: Int,
        end: WriteEnd,
    ): StreamSendStep {
        val sent = q.api.connStreamSendStep(q.handle, stream, address, length, end == WriteEnd.Finish)
        return sent.fold(
            took = { took ->
                if (took > 0 || end == WriteEnd.Finish) output++
                sent
            },
            full = {
                when (capacity(stream)) {
                    QUICHE_ERR_STREAM_STOPPED.toLong(), QUICHE_ERR_INVALID_STREAM_STATE.toLong() -> STOPPED
                    else -> sent
                }
            },
            refused = { _, _ -> sent },
        )
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
    ): Int {
        output++
        return q.api.connStreamShutdown(
            q.handle,
            stream,
            when (side) {
                ShutdownSide.Read -> 0
                ShutdownSide.Write -> 1
            },
            code,
        )
    }

    private companion object {
        /** A send refused because the peer stopped the stream; quiche no longer says with which code. */
        val STOPPED = StreamSendStep.stopped(QuicAppErrorCode(0L))
    }
}

/** quiche's raw answer: the bytes it took, or a negative quiche error code. */
internal fun StreamSendStep.code(): Int = fold(took = { it }, full = { QUICHE_ERR_DONE }, refused = { code, _ -> code })

/** The peer's application error code of a refused send; 0 when the peer sent none. */
internal fun StreamSendStep.peer(): QuicAppErrorCode = fold(took = { NO_PEER_CODE }, full = { NO_PEER_CODE }, refused = { _, peer -> peer })

private val NO_PEER_CODE = QuicAppErrorCode(0L)
