package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ReplySource
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.SpareId
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.ServerCounts
import com.ditchoom.socket.quic.core.pool.Hold
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.udp.ArrivedOn
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.SockAddr
import kotlin.random.Random

/** Whether an admission chunk left Initials that this pass may still start. */
internal enum class Admitted { Done, More }

/** How a new connection's id and original destination id are decided. */
internal sealed interface Admit {
    /** No address validation: the server picks the id and also answers to the id the client chose. */
    data object Fresh : Admit

    /** A valid NEW_TOKEN token: as [Fresh], and the client's address counts as validated (RFC 9000 §8.1.3). */
    data object Proven : Admit

    /** A valid Retry token: the client already uses the id the server picked, in the packet's destination id. */
    class Redeemed(
        val token: TokenCheck.Valid,
    ) : Admit
}

/** What [Admission.accept] made. */
internal sealed interface Accepted {
    class Peer(
        val peer: ServerPeer,
        val handle: QuicheConn,
    ) : Accepted

    data object Refused : Accepted
}

/**
 * Admits, holds or drops an Initial that starts a handshake. Every such Initial is first held in the [backlog] while
 * the loop reads its socket, so reading never waits on a handshake; the loop then starts them [CHUNK] at a time
 * between socket reads, at most [AdmissionBounds.perPass] in a pass, and never more than
 * [AdmissionBounds.inProgress] at once. The only file that calls `quiche_accept`. Loop thread only.
 */
@LoopOnly
internal class Admission(
    private val api: QuicheApi,
    private val bounds: AdmissionBounds,
    val backlog: InitialBacklog,
    private val parts: ServerParts,
    private val hooks: ServerHooks,
    private val hold: MemoryHold,
    private val random: Random,
    private val counts: ServerCounts,
) {
    /** Handshakes begun and not finished; any thread that is inside one of them counts it down. */
    private val inProgress = Counter()
    private var startedThisPass = 0
    private var chunkEnd = 0

    /** The id of the connection [accept] made last, as its route keys it. */
    val newCid = CidRead()

    fun passBegins() {
        startedThisPass = 0
    }

    fun handshakesInProgress(): Long = inProgress.value()

    /** A fresh random id and reset token for an accepted connection to hand its peer. */
    fun spareId(): SpareId = SpareId(random.nextLong(), random.nextLong(), random.nextLong(), random.nextLong())

    /** Holds the Initial at [datagram] for the next admission chunk, or drops it when a bound is reached. */
    fun hold(
        datagram: NativeBytes,
        length: Int,
        from: PathKey,
        to: ArrivedOn,
    ) {
        if (full()) return counts.admissionDropped()
        when (backlog.hold(datagram, length, from, to)) {
            Held.Yes -> counts.backlogHeld()
            Held.Full -> counts.backlogDropped()
        }
    }

    fun chunkBegins() {
        chunkEnd = minOf(bounds.perPass, startedThisPass + CHUNK)
    }

    /** Whether the chunk may take the oldest held Initial. */
    fun mayTakeMore(): Boolean = backlog.size > 0 && startedThisPass < chunkEnd && !full()

    /** After a chunk: whether this pass may still start more. */
    fun afterChunk(): Admitted = if (backlog.size > 0 && startedThisPass < bounds.perPass && !full()) Admitted.More else Admitted.Done

    /** Whether held Initials wait for a later pass that may start them: this pass's count or receive time ran out. */
    fun owesPass(): Boolean = backlog.size > 0 && !full()

    /**
     * Starts a connection for the Initial at [datagram], read into [header], that came [from] and arrived [to]; its id is
     * left in [newCid]. The connection's local address is the one the Initial arrived on, which every reply leaves from.
     */
    fun accept(
        how: Admit,
        datagram: NativeBytes,
        header: PacketHeader,
        from: PathKey,
        to: ArrivedOn,
        scratch: DemuxScratch,
    ): Accepted {
        if (full()) {
            counts.admissionDropped()
            return Accepted.Refused
        }
        startedThisPass++
        val scidAddress: Long
        val odcidAddress: Long
        val odcidLength: Int
        when (how) {
            is Admit.Fresh, is Admit.Proven -> {
                val c0 = random.nextLong()
                val c1 = random.nextLong()
                scratch.pickScid(c0, c1)
                newCid.set(c0, c1, 0L, QUIC_ISSUED_CONN_ID_LEN)
                scidAddress = scratch.newScid.address
                odcidAddress = 0L
                odcidLength = 0
            }
            is Admit.Redeemed -> {
                newCid.set(header.cid.c0, header.cid.c1, 0L, QUIC_ISSUED_CONN_ID_LEN)
                scidAddress = datagram.address + header.dcidOffset
                odcidAddress = scratch.token.address + how.token.originalDcidOffset
                odcidLength = how.token.originalDcidLength
            }
        }
        val peerAddress =
            when (val encoded = hooks.encode(from)) {
                is Encoding.Encoded -> encoded.sockAddr
                is Encoding.Unencodable -> return refused()
            }
        val local =
            when (to) {
                is ArrivedOn.Unreported -> ConnLocal.Bound(parts.local, parts.localKey)
                is ArrivedOn.At ->
                    when (val encoded = hooks.encode(to.local)) {
                        is Encoding.Encoded -> ConnLocal.Arrived(encoded.sockAddr, to.local)
                        is Encoding.Unencodable -> {
                            peerAddress.free()
                            return refused()
                        }
                    }
            }
        val memory =
            when (val taken = PeerMemory.of(parts.factory)) {
                is PeerMemoryTake.Taken -> taken.memory
                is PeerMemoryTake.NotNative -> {
                    peerAddress.free()
                    local.free()
                    return refused()
                }
            }
        val claim =
            when (val held = hold.acquire()) {
                is Hold.Held -> held.claim
                is Hold.AlreadyFreed -> {
                    memory.free()
                    peerAddress.free()
                    local.free()
                    return refused()
                }
            }
        val handle =
            api.accept(
                scidAddress,
                QUIC_ISSUED_CONN_ID_LEN,
                odcidAddress,
                odcidLength,
                local.sockAddr.address,
                local.sockAddr.length,
                peerAddress.address,
                peerAddress.length,
                parts.config,
            )
        if (handle.handle == 0L) {
            memory.free()
            peerAddress.free()
            local.free()
            claim.release()
            return refused()
        }
        inProgress.increment()
        counts.acceptedOne()
        counts.recvInfoBuilt()
        val address =
            ConnAddress(
                SockAddr(peerAddress.address, peerAddress.length),
                SockAddr(local.sockAddr.address, local.sockAddr.length),
                from,
                local.key,
                SendTarget.ServerReply(to = from, from = ReplySource.Recorded(local.key)),
            )
        val build = ConnBuild(parts, QuicheConnRef(api, handle), address, memory, inProgress)
        val opened = hooks.open(AcceptedPeer(from, newCid.c0, newCid.c1), build)
        return Accepted.Peer(ServerPeer(opened.conn, memory, peerAddress, local, opened, claim), handle)
    }

    private fun full(): Boolean = inProgress.value() >= bounds.inProgress

    private fun refused(): Accepted {
        counts.unknownDropped()
        return Accepted.Refused
    }

    private companion object {
        /** Handshakes started between two reads of the socket. */
        const val CHUNK = 16
    }
}
