@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.path

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuichePathEventType
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.PeerAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.RetiredId
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.decodePathKey
import com.ditchoom.socket.quic.encodeToNative
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Whether quiche is validating a peer address the server has seen. */
internal enum class PeerCheck { Validating, Done }

/**
 * One address a server's peer sent from: as the socket reported it ([from]), as quiche reads it ([key]), the receive
 * info quiche is handed with its datagrams, and the target its replies go to, from the server's own address.
 */
@Confined
internal class PeerAddr(
    val from: PathKey,
    val key: PathKey,
    val recvInfo: QuicheRecvInfo,
    val to: Outgoing.To,
    val sockAddr: Made,
) {
    val info = RecvFrom.Info(recvInfo)

    var check = PeerCheck.Done
        private set

    fun checking(now: PeerCheck) {
        check = now
    }
}

/** What a datagram's source address is told to quiche with. */
internal sealed interface RecvFrom {
    class Info(
        val recvInfo: QuicheRecvInfo,
    ) : RecvFrom

    /** The peer sent from more addresses than a connection follows: the datagram is dropped. */
    data object TooManyAddresses : RecvFrom
}

/** How many new ids the loop should route and hand this connection; any thread reads it, the thread inside sets it. */
@Published
internal class IdsWanted {
    private val wanted = AtomicInt(0)

    fun want(count: Int) = wanted.store(count)

    /** The loop takes what is wanted, once. */
    fun take(): Int = if (wanted.load() == 0) 0 else wanted.exchange(0)
}

/** The ids a peer retired and the loop has not yet unrouted: the thread inside adds them, the loop takes them. */
@Published
internal class IdsRetired {
    private val held = AtomicReference<List<RetiredId>>(emptyList())

    fun add(ids: List<RetiredId>) {
        while (true) {
            val seen = held.load()
            if (held.compareAndSet(seen, seen + ids)) return
        }
    }

    /** The loop takes what is held, once. */
    fun take(): List<RetiredId> = if (held.load().isEmpty()) emptyList() else held.exchange(emptyList())
}

/**
 * A server connection's paths: quiche follows a peer that moves (RFC 9000 §9), and this tells it where each datagram
 * came from and sends each reply where quiche addresses it, always from the server's own address. While quiche
 * validates a new peer address its datagrams are at most 1200 bytes. The loop routes the ids the peer may move with
 * ([IdsWanted]) before this connection hands them to quiche ([issue]). Inside the connection only.
 */
@Confined
internal class ServerPaths(
    private val q: QuicheConnRef,
    private val first: SinglePath,
    peerKey: PathKey,
    private val localKey: PathKey,
    private val factory: BufferFactory,
    private val ids: IdIssuer,
    val wanted: IdsWanted,
    val retired: IdsRetired,
    private val free: (QuicheRecvInfo, Made) -> Unit,
) {
    private val firstTo = Outgoing.To(first.target)
    private val fixed = Sorting.Fixed(firstTo)
    private val peers = ArrayList<PeerAddr>(1)
    private var events: EventMemory = EventMemory.None
    private val retiredIds = RetiredReader(factory)

    /** The index of the peer address the last datagram came from. */
    private var latest = 0

    init {
        peers.add(PeerAddr(peerKey, q.api.decodePathKey(first.peer.address), first.recvInfo, firstTo, Made.ByEdge))
    }

    val sendInfo get() = first.sendInfo

    fun read(): PathRead = first.read()

    fun stats(): QuicStatsRead = first.stats()

    /** What quiche is told about a datagram that came from [from]: a peer address not seen before is followed. */
    fun recvInfo(from: PathKey): RecvFrom {
        for (i in peers.indices) {
            if (peers[i].from == from) {
                latest = i
                return peers[i].info
            }
        }
        if (peers.size == MAX_PEERS && !evictOne()) return RecvFrom.TooManyAddresses
        val sockAddr = from.encodeToNative(factory)
        val recvInfo = q.api.recvInfoNew(sockAddr.address, sockAddr.length, first.local.address, first.local.length)
        val target = Outgoing.To(SendTarget.ServerReply(to = from, from = ReplySource.Recorded(localKey)))
        val peer = PeerAddr(from, q.api.decodePathKey(sockAddr.address), recvInfo, target, Made.Here(sockAddr))
        peer.checking(PeerCheck.Validating)
        peers.add(peer)
        latest = peers.size - 1
        wanted.want(q.api.connScidsLeft(q.handle).toInt())
        return peer.info
    }

    fun window(full: Window): Window = if (validating()) full.whileValidating() else full

    fun sorting(): Sorting = if (peers.size == 1) fixed else Sorting.ByAddress

    /** Where a datagram quiche wrote to the address at [to] goes. */
    fun outgoing(to: Long): Outgoing {
        for (i in peers.indices) if (q.addressIs(to, peers[i].key)) return peers[i].to
        return Outgoing.Dropped
    }

    /** The connection is established: the loop is asked for the ids a peer that moves needs. */
    fun established() = wanted.want(q.api.connScidsLeft(q.handle).toInt())

    /** Hands quiche the ids the loop routed to this connection. */
    fun issue(spare: List<SpareId>) {
        for (id in spare) ids.issue(q, id)
    }

    /** Reads the ids the peer retired, for the loop to stop routing, and quiche's path events while a peer address is validated. */
    fun step() {
        val gone = if (peers.size == 1) emptyList() else retiredIds.read(q)
        if (gone.isNotEmpty()) {
            retired.add(gone)
            // What the peer let go quiche may issue again: the loop routes new ids before they are handed over.
            wanted.want(q.api.connScidsLeft(q.handle).toInt())
        }
        if (!validating()) return
        val scratch =
            when (val taken = scratch()) {
                is EventsTaken.Taken -> taken.scratch
                is EventsTaken.NotNative -> return
            }
        while (true) {
            val type =
                when (val news = scratch.next(q)) {
                    is EventNext.Event -> news.type
                    is EventNext.Drained -> return
                }
            val settles =
                type == QuichePathEventType.Validated || type == QuichePathEventType.FailedValidation || type == QuichePathEventType.Closed
            if (!settles) continue
            for (i in peers.indices) if (q.addressIs(scratch.peer, peers[i].key)) peers[i].checking(PeerCheck.Done)
        }
    }

    /** Hands [each] every peer address's receive info and who made its sockaddr, and frees the paths' own memory. */
    fun made(each: (QuicheRecvInfo, Made) -> Unit) {
        for (peer in peers) each(peer.recvInfo, peer.sockAddr)
        when (val held = events) {
            is EventMemory.Held -> held.scratch.free()
            is EventMemory.None -> Unit
        }
        ids.free()
        retiredIds.free()
    }

    /** Frees the oldest address that is neither the latest nor being validated; false when there is none. */
    private fun evictOne(): Boolean {
        for (i in peers.indices) {
            if (i == latest || peers[i].check == PeerCheck.Validating) continue
            free(peers[i].recvInfo, peers[i].sockAddr)
            peers.removeAt(i)
            if (i < latest) latest--
            return true
        }
        return false
    }

    /** Whether the peer's address is validated: not while a new address of its is (RFC 9000 §8.1, §9.3). */
    fun address(): PeerAddress = if (validating()) PeerAddress.Unvalidated else PeerAddress.Validated

    private fun validating(): Boolean {
        for (i in peers.indices) if (peers[i].check == PeerCheck.Validating) return true
        return false
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

    private companion object {
        /** Peer addresses one connection holds at once; the oldest that is settled makes room for a new one. */
        const val MAX_PEERS = 8
    }
}
