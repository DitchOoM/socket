
package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.Arrival
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.conn.EarlyReport
import com.ditchoom.socket.quic.core.conn.HeldReads
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlinx.coroutines.Runnable

/** An address as quiche reads it, or that it has no form quiche takes. */
internal sealed interface Encoding {
    class Encoded(
        val sockAddr: EncodedSockAddr,
    ) : Encoding

    data object Unencodable : Encoding
}

/** Who the server accepted, told to the edge before the connection is built: its address and the id the server picked. */
internal class AcceptedPeer(
    val peer: PathKey,
    val scid0: Long,
    val scid1: Long,
)

/** What the edge made of an accepted connection: the connection, and what frees the edge's own part of it. */
internal class Opened(
    val conn: Conn,
    val freed: Runnable,
)

/**
 * What a server's demux asks of the edge above it, handed down when the server is built. [encode] encodes a sender, or
 * the local address its datagram arrived on, for quiche; [open] builds the caller-facing side of an accepted
 * connection around the [ConnBuild] it is given and returns the connection that build made.
 */
internal class ServerHooks(
    val encode: (PathKey) -> Encoding,
    val open: (AcceptedPeer, ConnBuild) -> Opened,
)

private enum class HandshakePhase { Running, Done }

/**
 * An accepted connection's sink: the edge's [inner] sink, and the count of handshakes in progress that [Admission]
 * bounds, decremented once when the handshake completes or the connection ends first.
 */
@Confined
internal class HandshakeSink(
    private val inner: ConnSink,
    private val inProgress: Counter,
) : ConnSink {
    private var phase = HandshakePhase.Running

    override fun established(report: ConnectReport) {
        finish()
        inner.established(report)
    }

    override fun earlyData(report: EarlyReport) = inner.earlyData(report)

    override fun incoming(
        stream: QuicStreamId,
        arrival: Arrival,
    ) = inner.incoming(stream, arrival)

    override fun ticket(
        bytes: NativeBytes,
        length: Int,
    ) = inner.ticket(bytes, length)

    override fun ended(
        end: ConnEnd,
        held: HeldReads,
        outcome: CloseOutcome,
    ) {
        finish()
        inner.ended(end, held, outcome)
    }

    private fun finish() {
        if (phase == HandshakePhase.Done) return
        phase = HandshakePhase.Done
        inProgress.decrement()
    }
}
