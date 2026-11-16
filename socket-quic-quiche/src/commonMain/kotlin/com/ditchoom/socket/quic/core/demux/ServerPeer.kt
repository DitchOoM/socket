package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.CopySlot
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.sched.Entrant
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlinx.coroutines.Runnable

/** What [PeerMemory.of] answers. */
internal sealed interface PeerMemoryTake {
    class Taken(
        val memory: PeerMemory,
    ) : PeerMemoryTake

    /** The factory made memory without an address; nothing is kept. */
    data object NotNative : PeerMemoryTake
}

/** The native memory one accepted connection holds, from the server's factory, freed together once it is released. */
internal class PeerMemory private constructor(
    val copies: FixedPool<CopySlot>,
    val alpn: NativeBytes,
    val arena: NativeBytes,
    private val owned: List<NativeBytes>,
) {
    fun free() {
        for (bytes in owned) bytes.free()
    }

    companion object {
        fun of(
            factory: BufferFactory,
            copySlots: Int,
            datagramSize: Int,
            arenaBytes: Int,
        ): PeerMemoryTake {
            val owned = ArrayList<NativeBytes>(copySlots + 2)
            for (size in List(copySlots) { datagramSize } + listOf(MAX_ALPN, arenaBytes)) {
                when (val taken = NativeBytes.of(factory.allocate(size))) {
                    is Addressed.Native -> owned.add(taken.bytes)
                    is Addressed.NotAddressable -> {
                        for (bytes in owned) bytes.free()
                        return PeerMemoryTake.NotNative
                    }
                }
            }
            val copies = FixedPool(copySlots) { CopySlot(it, owned[it]) }
            return PeerMemoryTake.Taken(PeerMemory(copies, owned[copySlots], owned[copySlots + 1], owned))
        }

        private const val MAX_ALPN = 255
    }
}

/** What every connection a server accepts is built with: shared by all of them. */
internal class ServerParts(
    val rules: SlotRules,
    val factory: BufferFactory,
    val flight: FlightSize,
    val port: LoopPort,
    val socket: SendPort,
    val clock: LoopClock,
    val loopEntrant: Entrant,
    val config: QuicheConfig,
    /** The socket's bound address as quiche reads it, and as a key. */
    val local: EncodedSockAddr,
    val localKey: PathKey,
)

/**
 * One accepted connection, ready to be built once the edge hands over its sink and capture. Used once: [build] wraps
 * the sink in the handshake count [Admission] bounds.
 */
internal class ConnBuild(
    private val parts: ServerParts,
    private val q: QuicheConnRef,
    private val address: ConnAddress,
    private val memory: PeerMemory,
    private val inProgress: Counter,
) {
    /** Where the connection copies the negotiated ALPN; the edge reads it at establishment. */
    val alpn: PlatformBuffer get() = memory.alpn.owner

    fun build(
        sink: ConnSink,
        capture: TraceCapture,
    ): Conn =
        buildConn(
            rules = parts.rules,
            q = q,
            role = QuicRole.Server,
            address = address,
            memory = ConnMemory(memory.copies, parts.factory, memory.alpn, memory.arena),
            flight = parts.flight,
            capture = capture,
            port = parts.port,
            socket = parts.socket,
            clock = parts.clock,
            sink = HandshakeSink(sink, inProgress),
            loopEntrant = parts.loopEntrant,
            counts = ConnCounts(),
        )
}

/** Whether a peer is in its demux's list. */
internal enum class Listing { Listed, Unlisted }

/**
 * One accepted connection on a server's loop: the connection, its routes, its place in the demux's list, and what
 * frees its memory once quiche's is freed. Loop thread only.
 */
@LoopOnly
internal class ServerPeer(
    val conn: Conn,
    memory: PeerMemory,
    peerAddress: EncodedSockAddr,
    opened: Opened,
    hold: ServerHold,
) {
    var scidRoute: RouteLink<ServerPeer> = RouteLink.End
        private set
    var originalRoute: RouteLink<ServerPeer> = RouteLink.End
        private set
    var listing = Listing.Unlisted
        private set

    /** Where the peer is in its demux's list, while [listing] is [Listing.Listed]. */
    var index = 0
        private set

    /** Runs on the thread that freed quiche's memory: the connection's own memory, the edge's part, the server's hold. */
    val freed =
        Runnable {
            memory.free()
            peerAddress.free()
            opened.freed.run()
            hold.release()
        }

    fun routed(
        scid: RouteLink<ServerPeer>,
        original: RouteLink<ServerPeer>,
    ) {
        scidRoute = scid
        originalRoute = original
    }

    fun listedAt(at: Int) {
        listing = Listing.Listed
        index = at
    }

    fun unlisted() {
        listing = Listing.Unlisted
        scidRoute = RouteLink.End
        originalRoute = RouteLink.End
    }
}

/** Closes a server's connection with NO_ERROR, inside it: the server is closing. */
internal class ServerCloseWork : ConnWork() {
    override fun run(inside: Inside) = inside.close(QuicError.NoError)

    override fun gone(end: ConnEnd) = Unit
}
