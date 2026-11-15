@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.NativeSockAddr
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnEnd
import com.ditchoom.socket.quic.core.conn.ConnEvents
import com.ditchoom.socket.quic.core.conn.ConnFreed
import com.ditchoom.socket.quic.core.conn.ConnPath
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.DatagramSlots
import com.ditchoom.socket.quic.core.edge.CoreConnection
import com.ditchoom.socket.quic.core.loop.ConnHost
import com.ditchoom.socket.quic.core.loop.Entrant
import com.ditchoom.socket.quic.core.loop.Loop
import com.ditchoom.socket.quic.core.loop.LoopClock
import com.ditchoom.socket.quic.core.loop.LoopHost
import com.ditchoom.socket.quic.core.loop.LoopWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The handshakes a server has begun and not finished: what [Admission.inProgress] bounds. */
internal class Handshakes {
    private val running = kotlin.concurrent.atomics.AtomicInt(0)

    val inProgress: Int get() = running.load()

    fun began() {
        running.fetchAndAdd(1)
    }

    fun finished() {
        running.fetchAndAdd(-1)
    }
}

/** Where a connection's handshake stands, for the thread inside it. */
private sealed interface Handshake {
    object Running : Handshake

    object Done : Handshake
}

/**
 * What the server does with a connection once it exists: the handshake count, and offering it to the application when
 * it is established. Everything else is [inner]'s.
 */
internal class AcceptedEvents(
    private val inner: CoreConnection,
    private val handshakes: Handshakes,
    private val offer: (CoreConnection) -> Unit,
) : ConnEvents by inner {
    private var phase: Handshake = Handshake.Running

    override fun established(inside: Conn.Inside) {
        inner.established(inside)
        finishHandshake()
        offer(inner)
    }

    override fun closed(end: ConnEnd) {
        finishHandshake()
        inner.closed(end)
    }

    private fun finishHandshake() {
        if (phase === Handshake.Running) {
            phase = Handshake.Done
            handshakes.finished()
        }
    }
}

/**
 * One accepted connection on a server's loop: the connection, the address of its peer and the `recv_info` and send
 * target built from it once at accept, and the connection's place in the loop's touched list. It is the connection's
 * [ConnHost]: it lends the loop's clock, deadlines and lanes and tells the server when the loop is inside it.
 */
internal class ServerPeer(
    private val parts: ServerParts,
    private val loop: Loop,
    val peer: PathKey,
    val peerAddress: NativeSockAddr,
    path: ConnPath,
    api: QuicheApi,
    handle: QuicheConn,
    clock: LoopClock,
    events: ConnEvents,
    slots: DatagramSlots,
    factory: com.ditchoom.buffer.BufferFactory,
    val connection: CoreConnection,
    private val scope: CoroutineScope,
) : ConnHost by loop,
    PeerLink {
    val conn: Conn = Conn(api, handle, path, this, Entrant.loopTurn(loop = SERVER_LOOP_ID, generation = 1), clock, events, factory, slots)

    /** Loop thread only: the next connection the loop is inside this pass. */
    internal var nextTouched: PeerLink = PeerLink.NotQueued

    /** Loop thread only: the routes this connection has, to remove when it ends. */
    internal var scidRoute: RouteLink<ServerPeer> = RouteLink.End
    internal var originalRoute: RouteLink<ServerPeer> = RouteLink.End

    /** Loop thread only: where this connection is in the loop's list of connections. */
    internal var slot = -1

    override fun entered() = parts.touched(this)

    /** What frees the connection's own memory, told once by the last thread out of it. */
    fun freed(shared: ServerResources): ConnFreed =
        ConnFreed {
            peerAddress.free()
            scope.cancel()
            shared.release()
        }

    /** Closes the connection with [error], inside it. */
    fun close(error: QuicError) = conn.submit(CloseWork(error))

    private class CloseWork(
        private val error: QuicError,
    ) : ConnWork() {
        override fun run(inside: Conn.Inside) = inside.close(error)

        override fun gone() = Unit
    }

    private companion object {
        const val SERVER_LOOP_ID = 0
    }
}

/** Where a [ServerPeer] stands in the loop's list of connections touched in a pass. */
internal sealed interface PeerLink {
    /** Not in the list. */
    object NotQueued : PeerLink

    /** The last of the list. */
    object End : PeerLink
}

/** Loop work: a connection ended, so its routes go and its memory is released. */
internal class Unroute(
    private val parts: ServerParts,
    private val peer: ServerPeer,
) : LoopWork() {
    override fun run(loop: LoopHost) = parts.unroute(peer)

    // The loop has stopped: nothing routes any more, and the connection's memory is released.
    override fun gone() = parts.release(peer)
}
