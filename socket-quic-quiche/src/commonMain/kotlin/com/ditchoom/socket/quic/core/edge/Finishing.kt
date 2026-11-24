package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Sender
import com.ditchoom.socket.quic.core.stream.Side
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Streams
import com.ditchoom.socket.quic.core.stream.WriteEnd
import com.ditchoom.socket.quic.core.stream.code
import com.ditchoom.socket.quic.core.stream.sender

/**
 * Where a send that ends our side of a stream left it: still open (quiche took none of its bytes and not its FIN), the
 * FIN taken with the last byte, or reset because it stopped after quiche took only part of its bytes.
 */
internal enum class Fin { Open, Taken, Cut }

/** What a write that ends our side came to, when it raised nothing. */
internal sealed interface Finished {
    /** quiche took every byte, [bytes] of them, and the FIN. */
    class All(
        val bytes: Int,
    ) : Finished

    /**
     * [failure] came after quiche took [accepted] of the bytes and before the last: our side was reset, so the peer never
     * reads the part as the whole.
     */
    class Cut(
        val accepted: Int,
        val failure: OpFailure,
    ) : Finished
}

/** Inside: hands quiche our FIN alone; past the close point, or once the connection ended, nothing is sent. */
internal fun StreamTable.finAlone(stream: QuicStreamId) {
    when (val sender = sender()) {
        is Streams.Open -> if (sender.send.send(stream, 0L, 0, WriteEnd.Finish).code() == 0) sendEnded(stream, Side.Finished)
        is Sender.Refused -> Unit
    }
}

/** Ends our side of a stream with its FIN alone, inside the connection, for a stream closed while a write ending it gave up. */
internal class FinWork(
    private val stream: QuicStreamId,
) : ConnWork() {
    override fun run(inside: Inside) = inside.streams.finAlone(stream)

    override fun gone(end: ConnEnd) = Unit
}
