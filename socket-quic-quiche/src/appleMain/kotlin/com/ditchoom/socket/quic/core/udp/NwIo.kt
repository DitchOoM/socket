@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NwConnection
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.copyOut
import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * The [DatagramBatchIo] of a client's Network.framework connection. A send hands each datagram to `nw_connection_send`
 * inside one `nw_connection_batch` while fewer than [MAX_IN_FLIGHT] sends await their completion; at the cap the rest
 * stay in the batch, and a completion wakes the loop to send them. Receiving is completion-driven: one receive is armed
 * at a time while [NwArrivals] has room, every arrival wakes the loop through [wake], and the loop copies each datagram
 * out. Network.framework reports a send's failure only on its completion: a datagram it could not send counts as lost,
 * and a connection that failed refuses every later send and receive as closed.
 */
@Queue
internal class NwIo(
    private val connection: NwConnection,
    private val life: NwLife,
    /** The peer every datagram comes from. */
    private val peer: PathKey,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) : DatagramBatchIo {
    private val inFlight = AtomicInt(0)
    private val blocked = AtomicInt(FLOWING)
    private val arming = AtomicInt(ARMED)
    private val arrivals = NwArrivals(MAX_ARRIVALS)
    private val sendOne = connection.sender(::completed)
    private val receiveOne = connection.receiver(::arrived)

    override val capability: BatchCapability = BatchCapability.Multi(MAX_IN_FLIGHT)

    /** Arms the first receive, once the connection is ready. */
    fun start() = receiveOne()

    override fun send(batch: SendBatch): SendBatchResult {
        when (life.view()) {
            is NwState.Ended -> return SendBatchResult.Partial(0, UdpError.SocketClosed)
            is NwState.Ready, is NwState.Starting -> Unit
        }
        val pending = batch.pending
        var handed = 0
        connection.batch {
            while (handed < pending && inFlight.incrementAndFetch() <= MAX_IN_FLIGHT) {
                sendOne(batch.storage.address + batch.offsetOf(handed), batch.lengthOf(handed))
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

    override fun receive(into: ReceiveBatch): ReceiveBatchResult {
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

    override fun probe(): SocketProbe =
        when (life.view()) {
            is NwState.Ended -> SocketProbe.Closed
            is NwState.Ready, is NwState.Starting -> SocketProbe.Open
        }

    /** The connection follows the device's paths itself: the same connection is the same endpoint. */
    override fun reopen(): Reopened = Reopened.SameEndpoint

    /** A connection's local endpoint is Network.framework's to choose: moving it is a new connection. */
    override fun moveTo(bind: PathKey): Moved = Moved.Unsupported

    override fun close(): SocketClosed {
        life.cancelled()
        connection.cancel()
        return SocketClosed
    }

    /** On Network.framework's queue: a send completed. */
    private fun completed() {
        inFlight.decrementAndFetch()
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
        private const val ARMED = 0
        private const val PAUSED = 1
        private const val FLOWING = 0
        private const val BLOCKED = 1
    }
}
