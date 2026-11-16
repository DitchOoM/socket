package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.conn.Collected
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.ready.Poller
import com.ditchoom.socket.quic.core.ready.SocketRef
import com.ditchoom.socket.quic.core.ready.WriteInterest
import com.ditchoom.socket.quic.core.sched.SendPort
import com.ditchoom.socket.quic.core.sched.SocketHealth
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
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

    /** Receives up to [MAX_PER_PASS] datagrams and hands them to the route. */
    fun receive(
        batch: ReceiveBatch,
        counts: LoopCounts,
    ) {
        if (input == Input.Drained || phase != Phase.Live) return
        var taken = 0
        while (taken < MAX_PER_PASS) {
            when (val got = io.receive(batch)) {
                is ReceiveBatchResult.Got -> {
                    taken += batch.count
                    deliver(batch)
                }
                is ReceiveBatchResult.Nothing -> {
                    input = Input.Drained
                    break
                }
                is ReceiveBatchResult.Failed -> {
                    deliver(batch)
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
            close(poller)
            onLeft.run()
            return Stay.Left
        }
        refreshInterest(poller)
        return Stay.Stays
    }

    /** The io threw: this socket's connection ends with it, and no other. */
    fun ioThrew(thrown: Thrown) {
        if (phase != Phase.Live) return
        phase = Phase.Dead
        endConn(ConnEnd.SocketFailed(SocketFault.Unnamed(thrown)))
    }

    /** The loop stopped with [end]: the connection ends, and the socket is closed. */
    fun loopEnded(
        end: ConnEnd,
        poller: Poller,
    ) {
        endConn(end)
        close(poller)
        onLeft.run()
    }

    /** The loop had stopped before this endpoint joined it: the connection ends and the socket is closed. */
    fun unplaced(end: ConnEnd) {
        endConn(end)
        phase = Phase.Gone
        io.close()
        onLeft.run()
    }

    private fun connEnded(): Boolean =
        when (route) {
            is Route.Fixed -> route.conn.life() is Life.Ended
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
        }

    private fun deliver(batch: ReceiveBatch) {
        when (route) {
            is Route.Fixed -> for (i in 0 until batch.count) route.conn.received(batch.bufferAt(i).bytes, batch.lengthAt(i))
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

    private fun close(poller: Poller) {
        if (phase == Phase.Gone) return
        phase = Phase.Gone
        poller.remove(token)
        io.close()
    }

    private companion object {
        /** Bounds one socket's receiving per pass so deadlines and work are not starved by a flood. */
        const val MAX_PER_PASS = 1024
    }
}
