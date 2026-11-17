package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.conn.NetworkChange
import com.ditchoom.socket.quic.core.conn.NetworkWork
import com.ditchoom.socket.quic.core.demux.Admitted
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.observe.TraceSection
import com.ditchoom.socket.quic.core.observe.traced
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.sched.LoopPort
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.Shutdown
import com.ditchoom.socket.quic.core.sched.SocketHealth
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlinx.coroutines.Runnable

private enum class Phase { Live, Dead, Gone }

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
    private var input = ReceiveInput.Maybe
    private var phase = Phase.Live
    private var interest = WriteInterest.None
    private var relocating: Relocating = Relocating.No
    private val closing = SocketClose(io, socket)

    fun placed(token: Int) {
        this.token = token
    }

    fun readable() {
        if (input != ReceiveInput.Backlog) input = ReceiveInput.Maybe
    }

    /** Whether the io holds datagrams the poller will never signal: the loop's next pass must not wait. */
    fun hasBacklog(): Boolean = phase == Phase.Live && input == ReceiveInput.Backlog

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
            is Route.Fixed -> {
                when (val moving = relocating) {
                    is Relocating.Yes -> relocating = route.answered(io.settleMove(), moving.prior)
                    is Relocating.No -> Unit
                }
                receiveWaiting(batch, counts, at)
            }
            is Route.Demux -> {
                route.demux.passBegins()
                receiveWaiting(batch, counts, at)
                while (route.demux.admitSome(send) == Admitted.More) {
                    input = ReceiveInput.Maybe
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
        if (input == ReceiveInput.Drained && readiness == Readiness.Polled) return
        var taken = 0
        while (taken < MAX_PER_PASS) {
            when (val got = traced(TraceSection.ReceiveBatch) { io.receive(batch) }) {
                is ReceiveBatchResult.Got, is ReceiveBatchResult.GotWithBacklog -> {
                    input = if (got is ReceiveBatchResult.GotWithBacklog) ReceiveInput.Backlog else ReceiveInput.Maybe
                    taken += batch.count
                    deliver(batch, at)
                }
                is ReceiveBatchResult.Nothing -> {
                    input = ReceiveInput.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    deliver(batch, at)
                    input = ReceiveInput.Drained
                    socket.receiveFailed(got.error)
                    break
                }
            }
        }
        counts.received(taken)
    }

    /**
     * Sends what lanes still hold, then serves the connection and sends its output. Once the connection has ended the
     * socket closes as [SocketClose] says.
     */
    fun serve(
        poller: Poller,
        now: Nanos,
        port: LoopPort,
    ): Stay {
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
                    Phase.Live -> traced(TraceSection.SendBatch) { socket.flush(send) }
                    Phase.Dead, Phase.Gone -> send.took(send.pending)
                }
            }
            if (collected != Collected.MoreWaiting || send.pending > 0) break
        }
        noticeDeath()
        if (connEnded() && (send.pending == 0 || phase != Phase.Live) && !socket.hasWaiting()) {
            when (phase) {
                Phase.Gone -> Unit
                Phase.Live, Phase.Dead ->
                    // With lanes out, the last thread out wakes the loop to ask again, the lane bound at the latest.
                    if (closing.ask(now, port) is Shutdown.Quiet) {
                        leave(poller)
                        closing.close()
                    }
            }
            if (phase == Phase.Gone) {
                onLeft.run()
                return Stay.Left
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
                input = ReceiveInput.Maybe
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

    /**
     * A client's socket moves to [bind]'s address on a port of its own, and its connection is told in this pass: it probes
     * the path from there and migrates. A socket made without waiting tells it in the pass that finds it ready. One that
     * cannot be opened there ends the connection; one that cannot move ends it as its address went away.
     */
    fun move(
        poller: Poller,
        bind: PathKey,
        prior: RttPrior,
    ) {
        val fixed =
            when (route) {
                is Route.Fixed -> route
                is Route.Demux -> return
            }
        if (phase != Phase.Live) return
        poller.remove(token)
        val moved = io.moveTo(bind)
        poller.add(socketRef, token)
        interest = WriteInterest.None
        input = ReceiveInput.Maybe
        relocating = fixed.answered(moved, prior)
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
        if (phase != Phase.Gone) {
            leave(poller)
            closing.stopped()
        }
        onLeft.run()
    }

    /** The loop had stopped before this endpoint joined it: the connection ends and the socket is closed. */
    fun unplaced(end: ConnEnd) {
        endConn(end)
        phase = Phase.Gone
        closing.stopped()
        onLeft.run()
    }

    private fun connEnded(): Boolean = route.ended()

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

    private fun endConn(end: ConnEnd) = route.endWith(end)

    /** The datagrams of one read, in a receive phase that began [at]. */
    private fun deliver(
        batch: ReceiveBatch,
        at: Nanos,
    ) {
        when (route) {
            is Route.Fixed -> for (i in 0 until batch.count) {
                route.conn.received(
                    batch.bufferAt(i).bytes,
                    batch.lengthAt(i),
                    batch.fromAt(i),
                    at,
                )
            }
            is Route.Demux -> traced(TraceSection.Demux) { route.demux.route(batch, send, at) }
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

    private fun leave(poller: Poller) {
        phase = Phase.Gone
        poller.remove(token)
    }

    private companion object {
        /** Bounds one socket's receiving per pass so deadlines and work are not starved by a flood. */
        const val MAX_PER_PASS = 1024
    }
}
