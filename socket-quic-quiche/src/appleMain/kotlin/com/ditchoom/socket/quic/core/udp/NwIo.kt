@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.NetworkErrorDomain
import com.ditchoom.socket.quic.NetworkRefusal
import com.ditchoom.socket.quic.NwConnection
import com.ditchoom.socket.quic.NwCreated
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.Queue
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The connection carrying the datagrams, and its ticket. */
private class Carried(
    val wire: NwWire,
    val ticket: SocketTicket,
)

/**
 * The second Network.framework connection while a client moves: written by its state handler while it starts, read by
 * the loop, and by senders once the loop took it as [Open].
 */
private sealed interface NwSecond {
    /** No second connection. */
    data object None : NwSecond

    class Starting(
        val wire: NwWire,
        val ticket: SocketTicket,
    ) : NwSecond

    class Ready(
        val wire: NwWire,
        val ticket: SocketTicket,
    ) : NwSecond

    class Failed(
        val refusal: NetworkRefusal,
    ) : NwSecond

    /** The loop took the ready connection: its receives are armed and datagrams for its ticket go through it. */
    class Open(
        val wire: NwWire,
        val ticket: SocketTicket,
    ) : NwSecond
}

/**
 * The [DatagramBatchIo] of a client's Network.framework connection, held as a [NwWire]. A UDP connection never
 * re-homes on a path change: it fails within seconds. A move is therefore a second connection to the peer, made on
 * whatever path leads there now, beside the first; the loop is woken when it is ready and takes it on its next read.
 * The first keeps carrying until the loop promotes the second, and is cancelled then; a second that is closed instead
 * is cancelled and the first carries on. Nothing waits for Network.framework.
 */
@Queue
internal class NwIo(
    connection: NwConnection,
    life: NwLife,
    /** The peer every datagram comes from. */
    private val peer: PathKey,
    /** What a connection to the same peer is made from. */
    private val host: String,
    private val port: Int,
    /** Any thread: the loop has news to read. */
    private val wake: () -> Unit,
) : DatagramBatchIo {
    private val carried = AtomicReference(Carried(NwWire(connection, life, wake), SocketTicket.FIRST))
    private val second = AtomicReference<NwSecond>(NwSecond.None)

    override val capability: BatchCapability = BatchCapability.Multi(NwWire.MAX_IN_FLIGHT)

    /** Arms the first receive, once the connection is ready. */
    fun start() = carried.load().wire.start()

    override fun send(batch: SendBatch): SendBatchResult {
        val now = carried.load()
        var taken = 0
        while (taken < batch.pending) {
            val run = batch.runFrom(taken)
            val ticket = batch.socketOf(taken)
            if (ticket == now.ticket) {
                when (val sent = now.wire.send(batch, taken, run)) {
                    is SendBatchResult.AllTaken -> taken += run
                    is SendBatchResult.Partial -> return SendBatchResult.Partial(taken + sent.taken, sent.why)
                }
            } else {
                // A datagram for the second connection is best effort, and one for a connection that is gone goes nowhere.
                when (val beside = second.load()) {
                    is NwSecond.Open -> if (beside.ticket == ticket) beside.wire.send(batch, taken, run)
                    is NwSecond.None, is NwSecond.Starting, is NwSecond.Ready, is NwSecond.Failed -> Unit
                }
                taken += run
            }
        }
        return SendBatchResult.AllTaken
    }

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = carried.load().wire.receive(into, peer)

    override fun receiveSecond(into: ReceiveBatch): ReceiveBatchResult =
        when (val beside = second.load()) {
            is NwSecond.Open -> beside.wire.receive(into, peer)
            is NwSecond.None, is NwSecond.Starting, is NwSecond.Ready, is NwSecond.Failed -> ReceiveBatchResult.Nothing
        }

    override fun probe(): SocketProbe =
        when (
            carried
                .load()
                .wire.life
                .view()
        ) {
            is NwState.Ended -> SocketProbe.Closed
            is NwState.Ready, is NwState.Starting -> SocketProbe.Open
        }

    /** The connection follows the device's paths itself: the same connection is the same endpoint. */
    override fun reopen(): Reopened = Reopened.SameEndpoint

    /** Starts a second connection to the peer on the path that leads there now; [bind] is the address the loop expects it on. */
    override fun openBeside(
        bind: PathKey,
        ticket: SocketTicket,
    ): Beside =
        when (val created = NwConnection.create(host, port)) {
            is NwCreated.Refused -> Beside.Refused(Thrown(NetworkRefusal(NetworkErrorDomain.Unreported, 0)))
            is NwCreated.Created -> {
                val candidate = NwWire(created.connection, NwLife(), wake)
                val starting = NwSecond.Starting(candidate, ticket)
                cancelled(second.exchange(starting))
                candidate.connection.start { change ->
                    when (val settled = candidate.life.changed(change)) {
                        is NwSettled.First ->
                            when (val verdict = settled.verdict) {
                                is NwVerdict.Ready -> second.compareAndSet(starting, NwSecond.Ready(candidate, ticket))
                                is NwVerdict.Refused -> second.compareAndSet(starting, NwSecond.Failed(verdict.refusal))
                            }
                        is NwSettled.Not -> Unit
                    }
                    wake()
                }
                Beside.Arriving
            }
        }

    override fun settleBeside(): Beside =
        when (val seen = second.load()) {
            is NwSecond.None, is NwSecond.Starting -> Beside.Arriving
            // Taken already: the loop asks only while it waits for one, so this answer never reaches it.
            is NwSecond.Open -> Beside.Arriving
            is NwSecond.Failed -> {
                second.compareAndSet(seen, NwSecond.None)
                Beside.Refused(Thrown(seen.refusal))
            }
            is NwSecond.Ready -> {
                val open = NwSecond.Open(seen.wire, seen.ticket)
                if (!second.compareAndSet(seen, open)) {
                    Beside.Arriving
                } else {
                    seen.wire.start()
                    Beside.Opened(seen.wire.connection.local(peer))
                }
            }
        }

    /** The second connection carries from now on; the first is retired once its sends complete. */
    override fun promote() {
        val open =
            when (val seen = second.load()) {
                is NwSecond.Open -> seen
                is NwSecond.None, is NwSecond.Starting, is NwSecond.Ready, is NwSecond.Failed -> return
            }
        carried.exchange(Carried(open.wire, open.ticket)).wire.retire()
        second.compareAndSet(open, NwSecond.None)
    }

    override fun closeSecond() = cancelled(second.exchange(NwSecond.None))

    override fun close(): SocketClosed {
        cancelled(second.exchange(NwSecond.None))
        carried.load().wire.retire()
        return SocketClosed
    }

    private fun cancelled(was: NwSecond) {
        when (was) {
            is NwSecond.Starting -> was.wire.retire()
            is NwSecond.Ready -> was.wire.retire()
            is NwSecond.Open -> was.wire.retire()
            is NwSecond.None, is NwSecond.Failed -> Unit
        }
    }
}
