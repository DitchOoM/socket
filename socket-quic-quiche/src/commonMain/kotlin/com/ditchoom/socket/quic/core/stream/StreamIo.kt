package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.StreamRecvStep
import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import kotlinx.coroutines.Runnable

/** Whether a write ends our side of the stream. */
internal enum class WriteEnd { More, Finish }

/** Which half a shutdown stops: the peer's sending (STOP_SENDING) or ours (RESET_STREAM). */
internal enum class ShutdownSide { Read, Write }

/**
 * A stream's bytes: with [StreamSend], the only file that sends, receives or shuts down stream data in quiche. Handing
 * quiche stream data is [StreamSend]'s alone, which only a [StreamTable] that is [Streams.Open] holds.
 */
@Confined
internal class StreamIo(
    private val q: QuicheConnRef,
    /** The code our side is reset with when a write that ends it stops part way. */
    val cutCode: QuicAppErrorCode,
) {
    /** Monotonic: sends that gave quiche bytes or a FIN, and shutdowns, each something the peer will be sent. */
    var output = 0L
        private set

    /** A send gave quiche bytes or a FIN. */
    fun counted() {
        output++
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
}

/**
 * Hands quiche stream data: what accepting it is. Held only by a [StreamTable] before its connection's close point. A
 * client's sends before its server settled 0-RTT are also handed to [copy]; after a rejected 0-RTT the kept sends are
 * replayed ([replay]) ahead of anything its callers send on the same streams.
 */
@Confined
internal class StreamSend(
    private val q: QuicheConnRef,
    private val io: StreamIo,
    private var copy: EarlyCopy,
) {
    private val early = EarlyReplay()
    private val raw: (Long, Long, Int, WriteEnd) -> StreamSendStep = { stream, address, length, end ->
        handed(QuicStreamId(stream), address, length, end)
    }

    /**
     * Sends on [stream], allocating nothing. quiche answers Done both when it can take nothing now and when it already
     * let the stream go: a stream our side has not ended is let go only once the peer stopped it, so that answer is
     * [STOPPED]. A stream still replaying takes nothing but a FIN alone, which follows the replay.
     */
    fun send(
        stream: QuicStreamId,
        address: Long,
        length: Int,
        end: WriteEnd,
    ): StreamSendStep {
        if (early.holds(stream.id)) {
            if (length > 0 || end == WriteEnd.More) return FULL
            early.finAfter(stream.id)
            return StreamSendStep.taken(0)
        }
        val sent = handed(stream, address, length, end)
        when (val to = copy) {
            is EarlyCopy.Off -> Unit
            is EarlyCopy.To ->
                sent.fold(
                    took = { took ->
                        if (took > 0 ||
                            length == 0
                        ) {
                            to.keep(stream.id, address, took, if (took == length) end else WriteEnd.More)
                        }
                    },
                    full = {},
                    refused = { _, _ -> },
                )
        }
        return sent
    }

    /** The server settled 0-RTT: nothing more is copied. */
    fun stopCopying() {
        copy = EarlyCopy.Off
    }

    /**
     * Replays [pieces], in order, ahead of later sends on their streams, from [start]; [done] runs once quiche took all
     * of them.
     */
    fun replay(
        pieces: List<Replayed>,
        done: Runnable,
        start: ReplayStart,
    ) = early.start(pieces, done, start, raw)

    /** A replay held for the server's check goes now. */
    fun releaseReplay() = early.release(raw)

    /** [stream] can take bytes: what it still has to replay goes first. */
    fun resume(stream: QuicStreamId): Replaying = early.resume(stream.id, raw)

    /** Past the close point nothing more is replayed. */
    fun dropReplay() = early.drop()

    private fun handed(
        stream: QuicStreamId,
        address: Long,
        length: Int,
        end: WriteEnd,
    ): StreamSendStep {
        val sent = q.api.connStreamSendStep(q.handle, stream, address, length, end == WriteEnd.Finish)
        return sent.fold(
            took = { took ->
                if (took > 0 || end == WriteEnd.Finish) io.counted()
                sent
            },
            full = {
                when (io.capacity(stream)) {
                    QUICHE_ERR_STREAM_STOPPED.toLong(), QUICHE_ERR_INVALID_STREAM_STATE.toLong() -> STOPPED
                    else -> sent
                }
            },
            refused = { _, _ -> sent },
        )
    }

    private companion object {
        /** A send refused because the peer stopped the stream; quiche no longer says with which code. */
        val STOPPED = StreamSendStep.stopped(QuicAppErrorCode(0L))

        /** quiche can take nothing now. */
        val FULL = StreamSendStep.of(StreamSendStep.QUICHE_ERR_DONE_CODE) { QuicAppErrorCode(0L) }
    }
}

/** quiche's raw answer: the bytes it took, or a negative quiche error code. */
internal fun StreamSendStep.code(): Int = fold(took = { it }, full = { QUICHE_ERR_DONE }, refused = { code, _ -> code })

/** The peer's application error code of a refused send; 0 when the peer sent none. */
internal fun StreamSendStep.peer(): QuicAppErrorCode = fold(took = { NO_PEER_CODE }, full = { NO_PEER_CODE }, refused = { _, peer -> peer })

private val NO_PEER_CODE = QuicAppErrorCode(0L)
