package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.demux.Admitted
import com.ditchoom.socket.quic.core.demux.ServerPhase
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.sched.Closer
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.Shutdown
import com.ditchoom.socket.quic.core.sched.SocketHealth
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable

/** A place in a loop's socket table: an endpoint, or empty. */
internal sealed interface LoopSlot {
    data object Empty : LoopSlot

    class Taken(
        val endpoint: Endpoint,
    ) : LoopSlot
}

/** Where an endpoint's datagrams go. */
internal sealed interface Route {
    /** A client's own connected socket carries exactly one connection. */
    class Fixed(
        val conn: Conn,
    ) : Route

    /** A server's unconnected socket carries every connection it accepted. */
    class Demux(
        val demux: com.ditchoom.socket.quic.core.demux.Demux,
    ) : Route
}

/** When an endpoint reads its socket. */
internal enum class Readiness {
    /** When the poller reported it readable, and until a read finds nothing. */
    Polled,

    /** Every pass: a socket whose readiness no poller sees, such as one driven by a test on virtual time. */
    EveryPass,
}

/** Whether a socket may hold datagrams: set by readiness, cleared by a receive that found none. */
private enum class Input { Maybe, Drained }

private enum class Phase { Live, Dead, Gone }

/** Whether an endpoint is still on its loop after a serve. */
internal enum class Stay { Stays, Left }

/** A socket on a loop and where its datagrams go. The loop thread only. */
@LoopOnly
internal class Endpoint(
    val socketRef: SocketRef,
    private val io: DatagramBatchIo,
    private val socket: SendPort,
    private val send: SendBatch,
    private val route: Route,
    /** Runs on the loop once the socket has left it, after the connection ended and its last output went out. */
    private val onLeft: Runnable,
    private val readiness: Readiness = Readiness.Polled,
) {
    var token = 0
        private set
    private var input = Input.Maybe
    private var phase = Phase.Live
    private var interest = WriteInterest.None

    fun placed(token: Int) {
        this.token = token
    }

    fun readable() {
        input = Input.Maybe
    }

    /**
     * Receives up to [MAX_PER_PASS] datagrams and hands them to the route. A server then starts held handshakes a
     * chunk at a time, reading the socket again after each chunk so its buffer never fills while they start.
     */
    fun receive(
        batch: ReceiveBatch,
        counts: LoopCounts,
        at: Nanos,
    ) {
        if (phase != Phase.Live) return
        when (route) {
            is Route.Fixed -> receiveWaiting(batch, counts, at)
            is Route.Demux -> {
                route.demux.passBegins()
                receiveWaiting(batch, counts, at)
                while (route.demux.admitSome(send) == Admitted.More) {
                    input = Input.Maybe
                    receiveWaiting(batch, counts, at)
                }
            }
        }
    }

    /** Reads until the socket is drained or [MAX_PER_PASS]; every datagram of this receive phase is stamped [at]. */
    private fun receiveWaiting(
        batch: ReceiveBatch,
        counts: LoopCounts,
        at: Nanos,
    ) {
        if (input == Input.Drained && readiness == Readiness.Polled) return
        var taken = 0
        while (taken < MAX_PER_PASS) {
            when (val got = io.receive(batch)) {
                is ReceiveBatchResult.Got -> {
                    taken += batch.count
                    deliver(batch, at)
                }
                is ReceiveBatchResult.Nothing -> {
                    input = Input.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    deliver(batch, at)
                    input = Input.Drained
                    socket.receiveFailed(got.error)
                    break
                }
            }
        }
        counts.received(taken)
    }

    /** Sends what lanes still hold, then serves the connection and sends its output. */
    fun serve(poller: Poller): Stay {
        noticeDeath()
        if (phase == Phase.Live) socket.flushWaiting()
        while (true) {
            val collected =
                when (route) {
                    is Route.Fixed -> route.conn.turn(send)
                    is Route.Demux -> route.demux.turn(send)
                }
            if (send.pending > 0) {
                when (phase) {
                    Phase.Live -> socket.flush(send)
                    Phase.Dead, Phase.Gone -> send.took(send.pending)
                }
            }
            if (collected != Collected.MoreWaiting || send.pending > 0) break
        }
        noticeDeath()
        if (connEnded() && (send.pending == 0 || phase != Phase.Live) && !socket.hasWaiting()) {
            when (close(poller, Closer.Loop)) {
                is Shutdown.Quiet -> {
                    onLeft.run()
                    return Stay.Left
                }
                // The last lane back wakes the loop, and this asks again.
                is Shutdown.LanesOut -> Unit
            }
        }
        refreshInterest(poller)
        return Stay.Stays
    }

    /**
     * The network under this socket changed: the socket is reopened on the same local address and port, and its
     * connection is told so in this pass, so it sends from the new socket at once instead of waiting out its
     * backed-off timers. A socket that cannot be reopened ends its connection.
     */
    fun rehome(poller: Poller) {
        if (phase != Phase.Live) return
        poller.remove(token)
        when (val reopened = io.reopen()) {
            is Reopened.SameEndpoint -> {
                poller.add(socketRef, token)
                interest = WriteInterest.None
                input = Input.Maybe
                when (route) {
                    is Route.Fixed -> route.conn.submitFromLoop(NetworkWork(NetworkChange.Rehomed))
                    is Route.Demux -> Unit
                }
            }
            is Reopened.Refused -> {
                phase = Phase.Gone
                endConn(ConnEnd.SocketFailed(SocketFault.ReopenRefused(reopened.thrown)))
            }
        }
    }

    /** The io threw: this socket's connection ends with it, and no other. */
    fun ioThrew(thrown: Thrown) {
        if (phase != Phase.Live) return
        phase = Phase.Dead
        endConn(ConnEnd.SocketFailed(SocketFault.Unnamed(thrown)))
    }

    /** The loop stopped with [end]: the connection ends, and the socket is closed now or by the last lane back. */
    fun loopEnded(
        end: ConnEnd,
        poller: Poller,
    ) {
        endConn(end)
        close(poller, Closer.LastLane)
        onLeft.run()
    }

    /** The loop had stopped before this endpoint joined it: the connection ends and the socket is closed. */
    fun unplaced(end: ConnEnd) {
        endConn(end)
        phase = Phase.Gone
        when (socket.shut(Closer.LastLane)) {
            is Shutdown.Quiet -> io.close()
            is Shutdown.LanesOut -> Unit
        }
        onLeft.run()
    }

    private fun connEnded(): Boolean =
        when (route) {
            is Route.Fixed -> route.conn.life() is Life.Ended
            is Route.Demux -> route.demux.phase() == ServerPhase.Stopped
        }

    private fun noticeDeath() {
        when (val health = socket.health()) {
            is SocketHealth.Live -> Unit
            is SocketHealth.Dead ->
                if (phase == Phase.Live) {
                    phase = Phase.Dead
                    endConn(ConnEnd.SocketFailed(health.fault))
                }
        }
    }

    private fun endConn(end: ConnEnd) =
        when (route) {
            is Route.Fixed -> route.conn.endWithLoop(end)
            is Route.Demux -> route.demux.endAll(end)
        }

    /** The datagrams of one read, in a receive phase that began [at]. */
    private fun deliver(
        batch: ReceiveBatch,
        at: Nanos,
    ) {
        when (route) {
            is Route.Fixed -> for (i in 0 until batch.count) route.conn.received(batch.bufferAt(i).bytes, batch.lengthAt(i), at)
            is Route.Demux -> route.demux.route(batch, send, at)
        }
        batch.release()
    }

    private fun refreshInterest(poller: Poller) {
        val wanted =
            if (phase == Phase.Live && (send.pending > 0 || socket.hasWaiting())) WriteInterest.Wanted else WriteInterest.None
        if (wanted == interest) return
        interest = wanted
        poller.interest(token, wanted)
    }

    /**
     * Closes the socket once no lane is out: nothing can send on a closed socket. With lanes out, [Closer.Loop] keeps
     * the socket on the loop until the last lane back wakes it; [Closer.LastLane] leaves it to that lane.
     */
    private fun close(
        poller: Poller,
        closer: Closer,
    ): Shutdown {
        if (phase == Phase.Gone) return Shutdown.Quiet
        val shut = socket.shut(closer)
        if (shut is Shutdown.LanesOut && closer == Closer.Loop) return shut
        phase = Phase.Gone
        poller.remove(token)
        if (shut is Shutdown.Quiet) io.close()
        return shut
    }

    private companion object {
        /** Bounds one socket's receiving per pass so deadlines and work are not starved by a flood. */
        const val MAX_PER_PASS = 1024
    }
}
