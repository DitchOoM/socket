package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.EncodedSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnAddress
import com.ditchoom.socket.quic.core.conn.ConnMemory
import com.ditchoom.socket.quic.core.conn.ConnSide
import com.ditchoom.socket.quic.core.conn.ConnSink
import com.ditchoom.socket.quic.core.conn.FlightSize
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.SlotRules
import com.ditchoom.socket.quic.core.conn.buildConn
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Counter
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.MemoryHold
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

/**
 * The native memory one accepted connection holds of its own, from the server's factory, freed once it is released:
 * only its ALPN scratch. Datagram copies and flight payload live in the loop's [LoopMemory], shared by every peer.
 */
internal class PeerMemory private constructor(
    val alpn: NativeBytes,
) {
    fun free() = alpn.free()

    companion object {
        fun of(factory: BufferFactory): PeerMemoryTake =
            when (val taken = NativeBytes.of(factory.allocate(MAX_ALPN))) {
                is Addressed.Native -> PeerMemoryTake.Taken(PeerMemory(taken.bytes))
                is Addressed.NotAddressable -> PeerMemoryTake.NotNative
            }

        private const val MAX_ALPN = 255
    }
}

/** What every connection a server accepts is built with: shared by all of them. */
internal class ServerParts(
    val rules: SlotRules,
    val factory: BufferFactory,
    val flight: FlightSize,
    /** The copy slots and flight arena every connection on the server's loop shares. */
    val shared: LoopMemory,
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
            side = ConnSide.Server,
            address = address,
            memory = ConnMemory(parts.shared, parts.factory, memory.alpn),
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
    hold: MemoryHold,
) {
    var scidRoute: RouteLink<ServerPeer> = RouteLink.End
        private set
    var originalRoute: RouteLink<ServerPeer> = RouteLink.End
        private set
    private val spares = ArrayList<Route<ServerPeer>>(0)
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

    /** A spare id handed to this peer's connection routes here until it ends. */
    fun routedSpare(route: Route<ServerPeer>) {
        spares.add(route)
    }

    /** Hands [each] every spare id's route, once: they go with the connection. */
    fun spareRoutes(each: (Route<ServerPeer>) -> Unit) {
        spares.forEach(each)
        spares.clear()
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
