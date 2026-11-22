package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicAppErrorCode
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_STREAM_STOPPED
import com.ditchoom.socket.quic.core.stream.Sender
import com.ditchoom.socket.quic.core.stream.ShutdownSide
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.stream.code
import com.ditchoom.socket.quic.core.stream.peer
import com.ditchoom.socket.quic.core.stream.sender

/** What one attempt of a lane did. */
internal sealed interface Moved {
    /** The lane finished what it was asked. */
    data object Done : Moved

    /** quiche can take or give nothing now: park until it can. */
    data object Again : Moved

    class Refused(
        val failure: OpFailure,
    ) : Moved
}

/** The direction a [StreamTransfer] moves bytes: into a stream's accumulator, or out of its scratch. */
internal sealed interface Lane

/**
 * Receives into a typed stream's [FrameAccumulator] what quiche has: at least one byte, or the stream's end. The one
 * receive per round trip fills the accumulator's whole free tail, so a frame arriving in pieces costs one operation per
 * piece and several frames in one read cost one.
 */
@Confined
internal class Inbound(
    val into: FrameAccumulator,
) : Lane {
    fun attempt(
        table: StreamTable,
        stream: QuicStreamId,
    ): Moved {
        val entry = table.entry(stream)
        return table.io.recv(stream, into.tailAddress(), into.free()).fold(
            more = { read -> if (read == 0) Moved.Again else received(read) },
            last = { read ->
                entry.finished()
                into.filled(read)
                into.peerFinished()
                Moved.Done
            },
            nothingYet = { Moved.Again },
            gone = { Moved.Refused(Raised.ConnEnded(ConnEnd.Released)) },
            reset = {
                entry.receiveEnded(Side.Reset)
                into.peerReset()
                Moved.Done
            },
            error = { code -> Moved.Refused(Raised.ReadRefused(stream, code)) },
        )
    }

    private fun received(read: Int): Moved {
        into.filled(read)
        return Moved.Done
    }
}

/**
 * Sends a typed stream's encoded frame from its [FrameScratch], straight from the scratch's memory: every byte of it, or
 * its FIN alone, parking while quiche's flow window is full. A frame the caller abandons after quiche took part of it
 * would leave the peer a half frame, so the stream is reset instead.
 */
@Confined
internal class Outbound(
    val from: FrameScratch,
    private val abandonedCode: QuicAppErrorCode,
) : Lane {
    private var end = WriteEnd.More
    private var sent = 0

    /** Starts the next send: the scratch's frame, ending our side after it when [ending] says so. */
    fun begin(ending: WriteEnd) {
        end = ending
        sent = 0
    }

    fun attempt(
        table: StreamTable,
        stream: QuicStreamId,
    ): Moved {
        val send =
            when (val sender = table.sender()) {
                is Streams.Open -> sender.send
                is Sender.Refused -> {
                    // A frame cut short would reach the peer as half a frame: the stream is reset, which is not data.
                    cutShort(table, stream)
                    return Moved.Refused(sender.why.raised())
                }
            }
        val length = from.length
        while (true) {
            val remaining = length - sent
            val answer = send.send(stream, from.address() + sent, remaining, end)
            val taken = answer.code()
            when {
                taken > 0 -> {
                    sent += taken
                    if (sent >= length) return done(table, stream)
                }
                taken == 0 && remaining == 0 -> return done(table, stream)
                taken == 0 || taken == QUICHE_ERR_DONE -> {
                    table.wantWritable(stream)
                    return Moved.Again
                }
                else -> {
                    if (taken == QUICHE_ERR_STREAM_STOPPED) table.stopped(stream)
                    return Moved.Refused(Raised.WriteRefused(stream, taken, answer.peer()))
                }
            }
        }
    }

    /** All of the frame went out, and our FIN after it when this send ends our side. */
    private fun done(
        table: StreamTable,
        stream: QuicStreamId,
    ): Moved {
        when (end) {
            WriteEnd.Finish -> table.sendEnded(stream, Side.Finished)
            WriteEnd.More -> Unit
        }
        return Moved.Done
    }

    /** The frame was abandoned after part of it went out: the peer would read half a frame, so the stream is reset. */
    fun abandoned(
        inside: Inside,
        stream: QuicStreamId,
    ) = cutShort(inside.streams, stream)

    private fun cutShort(
        table: StreamTable,
        stream: QuicStreamId,
    ) {
        if (sent > 0 && sent < from.length) {
            table.io.shutdown(stream, ShutdownSide.Write, abandonedCode.value)
            table.sendEnded(stream, Side.Reset)
            sent = 0
        }
    }
}

/** Resets a stream both ways with [code], inside the connection: the peer stops sending and its sends are refused. */
internal class ResetWork(
    private val stream: QuicStreamId,
    private val code: QuicAppErrorCode,
) : ConnWork() {
    override fun run(inside: Inside) = inside.streams.resetBothWays(stream, code)

    override fun gone(end: ConnEnd) = Unit
}

/**
 * Inside: resets [stream], which has our sending side, with [code]: RESET_STREAM for ours and STOP_SENDING for the
 * peer's, unless the stream has none.
 */
internal fun StreamTable.resetBothWays(
    stream: QuicStreamId,
    code: QuicAppErrorCode,
) {
    stopReading(stream, code)
    io.shutdown(stream, ShutdownSide.Write, code.value)
    sendEnded(stream, Side.Reset)
}

/** Resets our sending direction of a stream with [code] (RESET_STREAM), inside the connection. */
internal class ResetSendWork(
    private val stream: QuicStreamId,
    private val code: QuicAppErrorCode,
) : ConnWork() {
    override fun run(inside: Inside) {
        inside.streams.io.shutdown(stream, ShutdownSide.Write, code.value)
        inside.streams.sendEnded(stream, Side.Reset)
    }

    override fun gone(end: ConnEnd) = Unit
}

/** Stops the peer's sending direction of a stream with [code] (STOP_SENDING), inside the connection. */
internal class StopWork(
    private val stream: QuicStreamId,
    private val code: QuicAppErrorCode,
) : ConnWork() {
    override fun run(inside: Inside) = inside.streams.stopReading(stream, code)

    override fun gone(end: ConnEnd) = Unit
}

/** Abandons the receiving direction of a stream its caller closed: what still arrives is dropped. */
internal class AbandonWork(
    private val stream: QuicStreamId,
) : ConnWork() {
    override fun run(inside: Inside) = inside.streams.abandon(stream)

    override fun gone(end: ConnEnd) = Unit
}
