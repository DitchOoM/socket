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

/** Where a move to a new Network.framework connection stands: written by its state handler, read by the loop. */
private sealed interface NwMove {
    /** No move is under way. */
    data object Idle : NwMove

    class Starting(
        val wire: NwWire,
    ) : NwMove

    class Ready(
        val wire: NwWire,
    ) : NwMove

    class Failed(
        val refusal: NetworkRefusal,
    ) : NwMove
}

/**
 * The [DatagramBatchIo] of a client's Network.framework connection, held as a [NwWire]. A UDP connection never
 * re-homes on a path change: it fails within seconds. A move is therefore a second connection to the peer, made on
 * whatever path leads there now; the loop is woken when it is ready and swaps it in on its next read, and the first is
 * cancelled. The datagrams the first held unread are lost, as on any path change. Nothing waits for Network.framework.
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
    private val wire = AtomicReference(NwWire(connection, life, wake))
    private val move = AtomicReference<NwMove>(NwMove.Idle)

    override val capability: BatchCapability = BatchCapability.Multi(NwWire.MAX_IN_FLIGHT)

    /** Arms the first receive, once the connection is ready. */
    fun start() = wire.load().start()

    override fun send(batch: SendBatch): SendBatchResult = wire.load().send(batch)

    override fun receive(into: ReceiveBatch): ReceiveBatchResult = wire.load().receive(into, peer)

    override fun probe(): SocketProbe =
        when (wire.load().life.view()) {
            is NwState.Ended -> SocketProbe.Closed
            is NwState.Ready, is NwState.Starting -> SocketProbe.Open
        }

    /** The connection follows the device's paths itself: the same connection is the same endpoint. */
    override fun reopen(): Reopened = Reopened.SameEndpoint

    /** Starts a connection to the peer on the path that leads there now; [bind] is the address the loop expects it on. */
    override fun moveTo(bind: PathKey): Moved =
        when (val created = NwConnection.create(host, port)) {
            is NwCreated.Refused -> Moved.Refused(Thrown(NetworkRefusal(NetworkErrorDomain.Unreported, 0)))
            is NwCreated.Created -> {
                val candidate = NwWire(created.connection, NwLife(), wake)
                val starting = NwMove.Starting(candidate)
                cancelled(move.exchange(starting))
                candidate.connection.start { change ->
                    when (val settled = candidate.life.changed(change)) {
                        is NwSettled.First ->
                            when (val verdict = settled.verdict) {
                                is NwVerdict.Ready -> move.compareAndSet(starting, NwMove.Ready(candidate))
                                is NwVerdict.Refused -> move.compareAndSet(starting, NwMove.Failed(verdict.refusal))
                            }
                        is NwSettled.Not -> Unit
                    }
                    wake()
                }
                Moved.Arriving
            }
        }

    override fun settleMove(): Moved =
        when (val seen = move.load()) {
            is NwMove.Idle, is NwMove.Starting -> Moved.Arriving
            is NwMove.Failed -> {
                move.compareAndSet(seen, NwMove.Idle)
                Moved.Refused(Thrown(seen.refusal))
            }
            is NwMove.Ready -> {
                if (!move.compareAndSet(seen, NwMove.Idle)) {
                    Moved.Arriving
                } else {
                    wire.exchange(seen.wire).retire()
                    seen.wire.start()
                    Moved.To(seen.wire.connection.local(peer))
                }
            }
        }

    override fun close(): SocketClosed {
        cancelled(move.exchange(NwMove.Idle))
        wire.load().retire()
        return SocketClosed
    }

    private fun cancelled(was: NwMove) {
        when (was) {
            is NwMove.Starting -> was.wire.retire()
            is NwMove.Ready -> was.wire.retire()
            is NwMove.Idle, is NwMove.Failed -> Unit
        }
    }
}
