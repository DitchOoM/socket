@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: every Paths variant lives in one.

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
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.SockAddr
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative
import kotlin.random.Random

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
    fun move(
        now: Nanos,
        local: PathKey,
    ): MoveAnswer {
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
                is ProbeOutcome.Probed -> Move.Probing(from, Linked(at, probed.dcidSeq), 0)
                is ProbeOutcome.Rejected -> Move.Stranded(from, at, Unprobed.NoSpareId, q.retryAt(now, 0), LinkedId.None)
            }
        return MoveAnswer.Following
    }

    /**
     * Reads quiche's path events while a move is being validated, and migrates once it is; a move that found no path is
     * probed again when its [Retry] falls due.
     */
    fun step(now: Nanos): PathStep =
        when (val moving = move) {
            is Move.Probing -> follow(now, moving)
            is Move.Stranded -> reprobe(now, moving)
            is Move.Settled -> PathStep.Steady
        }

    /** Offers the time the next probe of a stranded endpoint falls due; offers nothing otherwise. */
    fun offerWake(sink: WakeSink) {
        when (val now = move) {
            is Move.Stranded -> sink.offer(now.retry.due)
            is Move.Settled, is Move.Probing -> Unit
        }
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

    /**
     * A stranded endpoint is tried again once its [Retry] falls due: a path that never had an id is probed with a fresh
     * one, one that failed validation is probed on the id it kept, and one that validated but could not be migrated to
     * is migrated to.
     */
    private fun reprobe(
        now: Nanos,
        stranded: Move.Stranded,
    ): PathStep {
        if (now.value < stranded.retry.due.value) return PathStep.Steady
        topUp()
        return when (stranded.why) {
            Unprobed.MigrationRefused -> migrate(now, stranded.from, stranded.at, LinkedId.None, stranded.retry.failed)
            Unprobed.NoSpareId, Unprobed.ValidationFailed -> {
                val at = sockAddrOf(stranded.at)
                move =
                    when (val probed = q.api.connProbePath(q.handle, at.address, at.length, first.peer.address, first.peer.length)) {
                        is ProbeOutcome.Probed -> Move.Probing(stranded.from, Linked(stranded.at, probed.dcidSeq), stranded.retry.failed)
                        is ProbeOutcome.Rejected -> strandedAgain(now, stranded)
                    }
                PathStep.Steady
            }
        }
    }

    private fun strandedAgain(
        now: Nanos,
        stranded: Move.Stranded,
    ): Move.Stranded = Move.Stranded(stranded.from, stranded.at, stranded.why, q.retryAt(now, stranded.retry.failed + 1), stranded.id)

    private fun follow(
        now: Nanos,
        probing: Move.Probing,
    ): PathStep {
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
            if (type == QuichePathEventType.Validated) {
                return migrate(now, probing.from, probing.to.local, LinkedId.Seq(probing.to.dcidSeq), probing.failed)
            }
            if (type == QuichePathEventType.FailedValidation || type == QuichePathEventType.Closed) {
                val retry = q.retryAt(now, probing.failed + 1)
                move = Move.Stranded(probing.from, probing.to.local, Unprobed.ValidationFailed, retry, LinkedId.Seq(probing.to.dcidSeq))
                return PathStep.Steady
            }
        }
    }

    private fun migrate(
        now: Nanos,
        from: Linked,
        to: LocalAddr,
        id: LinkedId,
        failed: Int,
    ): PathStep =
        when (val migrated = connMigrate(to)) {
            is MigrateOutcome.Migrated -> {
                // The old path's socket is gone: what it had in flight is resent from the new one now.
                val left = sockAddrOf(from.local)
                q.api.connRequeuePath(q.handle, left.address, left.length, first.peer.address, first.peer.length)
                retire(from.dcidSeq)
                spend(from.local)
                move = Move.Settled(Linked(to, migrated.dcidSeq))
                PathStep.Migrated
            }
            is MigrateOutcome.Rejected -> {
                when (id) {
                    is LinkedId.Seq -> retire(id.seq)
                    is LinkedId.None -> Unit
                }
                move = Move.Stranded(from, to, Unprobed.MigrationRefused, q.retryAt(now, failed + 1), LinkedId.None)
                PathStep.Steady
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
                when (val held = now.id) {
                    is LinkedId.Seq -> retire(held.seq)
                    is LinkedId.None -> Unit
                }
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
