@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NwConnection
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.copyOut
import com.ditchoom.socket.quic.core.observe.Queue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** Where a wire stands with its connection's cancel. */
private sealed interface WireEnd {
    data object Open : WireEnd

    /** Retired with sends still awaiting their completion: the last completion cancels it, or the drain bound does. */
    data object Draining : WireEnd

    data object Cancelled : WireEnd
}

/**
 * One Network.framework connection and everything that belongs to it alone: its state, the sends awaiting their
 * completion and the ring its receives fill. A send hands each datagram to `nw_connection_send` inside one
 * `nw_connection_batch` while fewer than [MAX_IN_FLIGHT] sends await their completion; at the cap the rest stay in the
 * batch, and a completion wakes the loop to send them. Receiving is completion-driven: one receive is armed at a time
 * while the ring has room, and every arrival wakes the loop through [wake]. A connection that failed refuses every
 * later send and receive as closed; Network.framework reports a send's failure only on its completion, so a datagram
 * it could not send counts as lost. A retired connection is cancelled once every send handed to it has completed,
 * since a cancel discards the sends Network.framework still holds: a closing client's last datagram is its
 * CONNECTION_CLOSE.
 */
@Queue
internal class NwWire(
    val connection: NwConnection,
    val life: NwLife,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) {
    private val inFlight = AtomicInt(0)
    private val blocked = AtomicInt(FLOWING)
    private val arming = AtomicInt(ARMED)
    private val end = AtomicReference<WireEnd>(WireEnd.Open)
    private val arrivals = NwArrivals(MAX_ARRIVALS)
    private val sendOne = connection.sender(::completed)
    private val receiveOne = connection.receiver(::arrived)

    /** Arms the first receive, once the connection is ready. */
    fun start() = receiveOne()

    /** Sends the [count] pending datagrams of [batch] from its [skip]th on; a partial answer counts from [skip]. */
    fun send(
        batch: SendBatch,
        skip: Int,
        count: Int,
    ): SendBatchResult {
        when (life.view()) {
            is NwState.Ended -> return SendBatchResult.Partial(0, UdpError.SocketClosed)
            is NwState.Ready, is NwState.Starting -> Unit
        }
        val pending = count
        var handed = 0
        connection.batch {
            while (handed < pending && inFlight.incrementAndFetch() <= MAX_IN_FLIGHT) {
                sendOne(batch.storage.address + batch.offsetOf(skip + handed), batch.lengthOf(skip + handed))
                handed++
            }
        }
        if (handed == pending) return SendBatchResult.AllTaken
        // The increment that found the cap full took no place. A completion from now on wakes the loop; one that came
        // before the mark found nothing to wake, so the loop is told now.
        inFlight.decrementAndFetch()
        blocked.store(BLOCKED)
        if (inFlight.load() < MAX_IN_FLIGHT && blocked.compareAndSet(BLOCKED, FLOWING)) wake()
        return SendBatchResult.Partial(handed, UdpError.WouldBlock)
    }

    /** Copies what has arrived into [into], each datagram from [peer]. */
    fun receive(
        into: ReceiveBatch,
        peer: PathKey,
    ): ReceiveBatchResult {
        var got = 0
        while (true) {
            when (val arrival = arrivals.peek()) {
                is Arrival.Waiting -> break
                is Arrival.Ended -> return ReceiveBatchResult.Failed(UdpError.SocketClosed)
                is Arrival.Datagram -> {
                    val buffer = into.take()
                    if (buffer !is ReceiveBuffer) break
                    val length = copyOut(arrival.content, buffer.bytes.address, into.limits.maxDatagramSize)
                    into.received(buffer, length, peer, ArrivedOn.Unreported)
                    arrivals.pop()
                    got++
                }
            }
        }
        if (arrivals.hasRoom() && arming.compareAndSet(PAUSED, ARMED)) receiveOne()
        return if (got > 0) ReceiveBatchResult.Got else ReceiveBatchResult.Nothing
    }

    /**
     * Ends the connection for every later send and receive, and cancels it once the sends it holds have completed, or
     * [DRAIN_BOUND_NANOS] from now if one never does.
     */
    fun retire() {
        life.cancelled()
        if (!end.compareAndSet(WireEnd.Open, WireEnd.Draining)) return
        if (inFlight.load() == 0) {
            cancelOnce()
        } else {
            dispatch_after(
                dispatch_time(DISPATCH_TIME_NOW, DRAIN_BOUND_NANOS),
                dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.convert(), 0u),
            ) {
                cancelOnce()
            }
        }
    }

    private fun cancelOnce() {
        if (end.compareAndSet(WireEnd.Draining, WireEnd.Cancelled)) connection.cancel()
    }

    /** On Network.framework's queue: a send completed. The last one of a retired connection cancels it. */
    private fun completed() {
        if (inFlight.decrementAndFetch() == 0 && end.load() == WireEnd.Draining) cancelOnce()
        if (blocked.compareAndSet(BLOCKED, FLOWING)) wake()
    }

    /** On Network.framework's queue: one receive completed. The next is armed only while the ring has room. */
    private fun arrived(arrival: Arrival) {
        arrivals.add(arrival)
        when (arrival) {
            is Arrival.Datagram -> if (arrivals.hasRoom()) receiveOne() else arming.store(PAUSED)
            is Arrival.Ended, is Arrival.Waiting -> Unit
        }
        wake()
    }

    companion object {
        /** Sends awaiting their completion before the rest of a batch waits. */
        const val MAX_IN_FLIGHT = 16

        /** Datagrams received and not yet read by the loop before receiving pauses. */
        const val MAX_ARRIVALS = 64

        /** How long a retired connection waits for its sends to complete before it is cancelled with them. */
        const val DRAIN_BOUND_NANOS = 1_000_000_000L
        private const val ARMED = 0
        private const val PAUSED = 1
        private const val FLOWING = 0
        private const val BLOCKED = 1
    }
}
