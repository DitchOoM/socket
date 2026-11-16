package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.MigrateOutcome
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.PeerMigrationPermission
import com.ditchoom.socket.quic.ProbeOutcome
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.connPeerMigrationPermission
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative
import kotlin.random.Random

/**
 * One local endpoint of a client: its address as quiche reads it ([key]), and the receive info for datagrams that
 * arrive on it. [made] says whose its sockaddr and receive info are, and so who frees them.
 */
internal class LocalAddr(
    val key: PathKey,
    val recvInfo: QuicheRecvInfo,
    val made: Made,
) {
    val info = RecvFrom.Info(recvInfo)
}

/** Who made a [LocalAddr]'s sockaddr and receive info. */
internal sealed interface Made {
    /** The connect's own, freed with the path the connection was opened on. */
    data object ByEdge : Made

    class Here(
        val sockAddr: EncodedSockAddr,
    ) : Made
}

/** A local endpoint and the destination connection id quiche linked to its path: the two never separate. */
internal class Linked(
    val local: LocalAddr,
    val dcidSeq: Long,
)

/** Why a moved socket's endpoint has no path being validated. */
internal enum class Unprobed { NoSpareId, ValidationFailed, MigrationRefused }

/** Where a client's path stands. Every destination id it holds is in exactly one [Linked]; the step that drops one retires it. */
internal sealed interface Move {
    /** The connection's one path leaves from [on]. */
    class Settled(
        val on: Linked,
    ) : Move

    /** The socket moved to [to], whose path is being validated; quiche's active path is still [from]. */
    class Probing(
        val from: Linked,
        val to: Linked,
    ) : Move

    /** The socket moved to [at], whose path is not being validated; quiche's active path is still [from]. */
    class Stranded(
        val from: Linked,
        val at: LocalAddr,
        val why: Unprobed,
    ) : Move
}

/** What a client asked to move answers. */
internal enum class MoveAnswer {
    /** The new endpoint's path is being validated, or has no spare id to be. */
    Following,

    /** The peer forbade migration: the connection cannot follow its socket. */
    Refused,
}

/** What one serve's look at the paths found. */
internal enum class PathStep { Steady, Migrated }

/**
 * A client's paths. Its socket has one local endpoint at a time; when the socket moves, the new endpoint's path is
 * probed (RFC 9000 §8.2) with datagrams of at most 1200 bytes, the connection migrates to it once validated, and the
 * id of the path it left is retired in the same step. Datagrams quiche writes for a path whose socket is gone are
 * dropped. Inside the connection only.
 */
@Confined
internal class ClientPaths(
    private val q: QuicheConnRef,
    private val first: SinglePath,
    private val factory: BufferFactory,
    private val ids: IdIssuer,
    private val random: Random,
    private val free: (QuicheRecvInfo, Made) -> Unit,
) {
    private val toPeer = Outgoing.To(first.target)
    private val fixed = Sorting.Fixed(toPeer)
    private var move: Move = Move.Settled(Linked(LocalAddr(q.api.decodePathKey(first.local.address), first.recvInfo, Made.ByEdge), 0L))
    private var events: EventMemory = EventMemory.None
    private var previous: Previous = Previous.None
    private val retiredIds = RetiredReader(factory)

    val sendInfo get() = first.sendInfo

    fun read(): PathRead = first.read()

    /** What quiche is told about a datagram that arrived: it came on the socket's endpoint of now. */
    fun recvFrom(): RecvFrom = socket().info

    fun window(full: Window): Window =
        when (move) {
            is Move.Settled -> full
            is Move.Probing, is Move.Stranded -> full.whileValidating()
        }

    fun sorting(): Sorting =
        when (move) {
            is Move.Settled -> fixed
            is Move.Probing, is Move.Stranded -> Sorting.ByAddress
        }

    /** Where a datagram quiche wrote from the local address at [from] goes: out of the socket, if it is the socket's. */
    fun outgoing(from: Long): Outgoing = if (q.addressIs(from, socket().key)) toPeer else Outgoing.Dropped

    /** The connection is established: the peer is given the spare ids a move needs. */
    fun established(): Issued = ids.issueAll(q, random)

    /** The socket now leaves from [local]: the path from it is probed, and an earlier unfinished move is dropped. */
    fun move(local: PathKey): MoveAnswer {
        when (q.api.connPeerMigrationPermission(q.handle)) {
            is PeerMigrationPermission.Permitted -> Unit
            is PeerMigrationPermission.Forbidden, is PeerMigrationPermission.NotYetNegotiated -> return MoveAnswer.Refused
        }
        topUp()
        val sockAddr = local.encodeToNative(factory)
        val at = LocalAddr(q.api.decodePathKey(sockAddr.address), recvInfoAt(sockAddr), Made.Here(sockAddr))
        val from = abandon()
        move =
            when (val probed = q.api.connProbePath(q.handle, sockAddr.address, sockAddr.length, first.peer.address, first.peer.length)) {
                is ProbeOutcome.Probed -> Move.Probing(from, Linked(at, probed.dcidSeq))
                is ProbeOutcome.Rejected -> Move.Stranded(from, at, Unprobed.NoSpareId)
            }
        return MoveAnswer.Following
    }

    /** Reads quiche's path events while a move is being validated, and migrates once it is. */
    fun step(): PathStep =
        when (val now = move) {
            is Move.Probing -> follow(now)
            is Move.Settled, is Move.Stranded -> PathStep.Steady
        }

    /** Whether the socket's endpoint has no validated path yet: an idle end then means no usable path was found. */
    fun unsettled(): Boolean = move !is Move.Settled

    /** Hands [each] every endpoint this connection made, for its release, and frees the paths' own memory. */
    fun made(each: (LocalAddr) -> Unit) {
        when (val held = previous) {
            is Previous.Held -> each(held.local)
            is Previous.None -> Unit
        }
        when (val now = move) {
            is Move.Settled -> each(now.on.local)
            is Move.Probing -> {
                each(now.from.local)
                each(now.to.local)
            }
            is Move.Stranded -> {
                each(now.from.local)
                each(now.at)
            }
        }
        when (val held = events) {
            is EventMemory.Held -> held.scratch.free()
            is EventMemory.None -> Unit
        }
        ids.free()
        retiredIds.free()
    }

    private fun follow(probing: Move.Probing): PathStep {
        val scratch =
            when (val taken = scratch()) {
                is EventsTaken.Taken -> taken.scratch
                is EventsTaken.NotNative -> return PathStep.Steady
            }
        while (true) {
            val type =
                when (val news = scratch.next(q)) {
                    is PathNews.Event -> news.type
                    is PathNews.Drained -> return PathStep.Steady
                }
            if (!q.addressIs(scratch.local, probing.to.local.key)) continue
            if (type == QuichePathEventType.Validated) return migrate(probing)
            if (type == QuichePathEventType.FailedValidation || type == QuichePathEventType.Closed) {
                retire(probing.to.dcidSeq)
                move = Move.Stranded(probing.from, probing.to.local, Unprobed.ValidationFailed)
                return PathStep.Steady
            }
        }
    }

    private fun migrate(probing: Move.Probing): PathStep {
        val to = probing.to.local
        return when (val migrated = connMigrate(to)) {
            is MigrateOutcome.Migrated -> {
                // The old path's socket is gone: what it had in flight is resent from the new one now.
                val left = sockAddrOf(probing.from.local)
                q.api.connRequeuePath(q.handle, left.address, left.length, first.peer.address, first.peer.length)
                retire(probing.from.dcidSeq)
                spend(probing.from.local)
                move = Move.Settled(Linked(to, migrated.dcidSeq))
                PathStep.Migrated
            }
            is MigrateOutcome.Rejected -> {
                retire(probing.to.dcidSeq)
                move = Move.Stranded(probing.from, to, Unprobed.MigrationRefused)
                PathStep.Steady
            }
        }
    }

    /**
     * Hands the peer the ids it may use for the paths of moves to come: each path uses one, and the peer retires those of
     * the paths it left. What it retired is read out of quiche, which keeps it until it is.
     */
    private fun topUp() {
        retiredIds.read(q)
        ids.issueAll(q, random)
    }

    private fun connMigrate(to: LocalAddr): MigrateOutcome {
        val local = sockAddrOf(to)
        return q.api.connMigrate(q.handle, local.address, local.length, first.peer.address, first.peer.length)
    }

    private fun sockAddrOf(local: LocalAddr): SockAddr =
        when (val made = local.made) {
            is Made.Here -> SockAddr(made.sockAddr.address, made.sockAddr.length)
            is Made.ByEdge -> first.local
        }

    /** Ends an unfinished move: a probe's id is retired and its endpoint spent. Answers the path data still rides. */
    private fun abandon(): Linked =
        when (val now = move) {
            is Move.Settled -> now.on
            is Move.Probing -> {
                retire(now.to.dcidSeq)
                spend(now.to.local)
                now.from
            }
            is Move.Stranded -> {
                spend(now.at)
                now.from
            }
        }

    /** [local] is left behind: it is kept as the previous endpoint, and the one kept before it is freed. */
    private fun spend(local: LocalAddr) {
        when (val held = previous) {
            is Previous.Held -> free(held.local.recvInfo, held.local.made)
            is Previous.None -> Unit
        }
        previous = Previous.Held(local)
    }

    private fun socket(): LocalAddr =
        when (val now = move) {
            is Move.Settled -> now.on.local
            is Move.Probing -> now.to.local
            is Move.Stranded -> now.at
        }

    private fun scratch(): EventsTaken =
        when (val held = events) {
            is EventMemory.Held -> EventsTaken.Taken(held.scratch)
            is EventMemory.None ->
                EventScratch.of(factory).also { taken ->
                    when (taken) {
                        is EventsTaken.Taken -> events = EventMemory.Held(taken.scratch)
                        is EventsTaken.NotNative -> Unit
                    }
                }
        }

    private fun recvInfoAt(local: EncodedSockAddr): QuicheRecvInfo =
        q.api.recvInfoNew(first.peer.address, first.peer.length, local.address, local.length)

    private fun retire(seq: Long) {
        // A refusal leaves the id with quiche until the connection ends; no path uses it.
        q.api.connRetireDcid(q.handle, seq)
    }
}
