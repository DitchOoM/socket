@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnectReport
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.Published
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch

/** A peer's address as quiche reads it, or that it has no form quiche takes. */
internal sealed interface PeerSockAddr {
    class Encoded(
        val sockAddr: EncodedSockAddr,
    ) : PeerSockAddr

    data object Unencodable : PeerSockAddr
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
 * What a server's demux asks of the edge above it, handed down when the server is built. [peerAddress] encodes a
 * sender for quiche; [open] builds the caller-facing side of an accepted connection around the [ConnBuild] it is
 * given and returns the connection that build made.
 */
internal class ServerHooks(
    val peerAddress: (PathKey) -> PeerSockAddr,
    val open: (AcceptedPeer, ConnBuild) -> Opened,
)

/** Memory every connection of a server shares, freed when the last holder lets go: the loop holds it, and each connection. */
@Published
internal class ServerHold(
    private val freeAll: Runnable,
) {
    private val holders = AtomicInt(1)

    fun acquire() {
        holders.fetchAndAdd(1)
    }

    fun release() {
        if (holders.decrementAndFetch() == 0) freeAll.run()
    }
}

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

    override fun incoming(stream: QuicStreamId) = inner.incoming(stream)

    override fun ended(end: ConnEnd) {
        finish()
        inner.ended(end)
    }

    private fun finish() {
        if (phase == HandshakePhase.Done) return
        phase = HandshakePhase.Done
        inProgress.decrement()
    }
}
