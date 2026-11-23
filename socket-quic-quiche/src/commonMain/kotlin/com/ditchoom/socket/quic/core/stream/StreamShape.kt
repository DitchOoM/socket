package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicStreamId

/** The two kinds of stream QUIC opens (RFC 9000 §2.1); each has a limit of its own, which the peer raises with MAX_STREAMS. */
internal enum class StreamKind {
    Bidirectional,
    Unidirectional,
    ;

    /** The first id of this kind a side with [role] opens; the next ones follow four apart. */
    fun firstLocal(role: QuicRole): Long = shapes(role).indexOf(local()).toLong()

    /** The first id of this kind the peer of a side with [role] opens. */
    fun firstPeer(role: QuicRole): Long = shapes(role).indexOf(peer()).toLong()

    /** What a stream of this kind is to the side that opened it. */
    fun local(): StreamShape =
        when (this) {
            Bidirectional -> StreamShape.LocalBidirectional
            Unidirectional -> StreamShape.SendOnly
        }

    private fun peer(): StreamShape =
        when (this) {
            Bidirectional -> StreamShape.PeerBidirectional
            Unidirectional -> StreamShape.ReceiveOnly
        }
}

/** A stream as one side sees it: who opened it, and which of its directions this side takes part in. */
internal enum class StreamShape {
    LocalBidirectional,
    PeerBidirectional,

    /** A unidirectional stream this side opened: it only sends. */
    SendOnly,

    /** A unidirectional stream the peer opened: this side only receives. */
    ReceiveOnly,
    ;

    fun kind(): StreamKind =
        when (this) {
            LocalBidirectional, PeerBidirectional -> StreamKind.Bidirectional
            SendOnly, ReceiveOnly -> StreamKind.Unidirectional
        }

    fun opener(): Opener =
        when (this) {
            LocalBidirectional, SendOnly -> Opener.Local
            PeerBidirectional, ReceiveOnly -> Opener.Peer
        }

    /** Where this side's sending stands on a new stream: open, or, on one it only receives, over before it begins. */
    fun sending(): Side =
        when (this) {
            LocalBidirectional, PeerBidirectional, SendOnly -> Side.Open
            ReceiveOnly -> Side.NotPart
        }

    /** Where this side's receiving stands on a new stream: open, or, on one it only sends, over before it begins. */
    fun receiving(): Side =
        when (this) {
            LocalBidirectional, PeerBidirectional, ReceiveOnly -> Side.Open
            SendOnly -> Side.NotPart
        }
}

/** Which side opened a stream. */
internal enum class Opener { Local, Peer }

/**
 * What [id] is to a side with [role]. With [kindOf], the one place a stream's direction and opener are read from its
 * id, whose two low bits say both (RFC 9000 §2.1: bit 0 the server opened it, bit 1 it is unidirectional).
 */
internal fun shapeOf(
    id: QuicStreamId,
    role: QuicRole,
): StreamShape = shapes(role)[(id.id and TYPE_BITS).toInt()]

/** The kind of stream [id] names, whichever side opened it. */
internal fun kindOf(id: QuicStreamId): StreamKind = KINDS[(id.id and TYPE_BITS).toInt()]

private val KINDS = listOf(StreamKind.Bidirectional, StreamKind.Bidirectional, StreamKind.Unidirectional, StreamKind.Unidirectional)

private fun shapes(role: QuicRole): List<StreamShape> =
    when (role) {
        QuicRole.Client -> CLIENT_SHAPES
        QuicRole.Server -> SERVER_SHAPES
    }

private const val TYPE_BITS = 3L

/** By the two low bits of an id: client bidirectional, server bidirectional, client unidirectional, server unidirectional. */
private val CLIENT_SHAPES =
    listOf(StreamShape.LocalBidirectional, StreamShape.PeerBidirectional, StreamShape.SendOnly, StreamShape.ReceiveOnly)
private val SERVER_SHAPES =
    listOf(StreamShape.PeerBidirectional, StreamShape.LocalBidirectional, StreamShape.ReceiveOnly, StreamShape.SendOnly)
