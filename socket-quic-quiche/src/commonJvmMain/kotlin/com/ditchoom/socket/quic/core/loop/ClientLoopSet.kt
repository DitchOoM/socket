@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.core.edge.addressOf
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What [ClientLoopSet.place] answers: a loop, or that the set was closed. */
internal sealed interface Placement {
    object SetClosed : Placement
}

private sealed interface Slot {
    object Unstarted : Slot

    class Running(
        val loop: ClientLoop,
    ) : Slot

    object Closed : Slot
}

/**
 * [size] client loops, each started on its first connection. A new connection goes to the loop with the fewest;
 * a loop that stopped is replaced by a fresh one. A loop with no connection stays parked in its selector: it is a
 * daemon thread, there are at most [size] of them, and a loop that exits and restarts would race the placement
 * that picked it.
 */
internal class ClientLoopSet(
    val size: Int,
    private val clock: LoopClock = LoopClock { System.nanoTime() },
) {
    private val slots = AtomicReferenceArray<Slot>(size).also { for (i in 0 until size) it.set(i, Slot.Unstarted) }

    /** Any thread. The loop for the next connection, counted against it, starting the loop if it has not started. */
    fun place(factory: BufferFactory): Placement {
        var best = 0
        var bestCount = Int.MAX_VALUE
        for (i in 0 until size) {
            val count =
                when (val slot = slots.get(i)) {
                    Slot.Unstarted -> 0
                    is Slot.Running -> slot.loop.connections.load()
                    Slot.Closed -> return Placement.SetClosed
                }
            if (count < bestCount) {
                best = i
                bestCount = count
            }
        }
        return when (val slot = slots.get(best)) {
            Slot.Closed -> Placement.SetClosed
            Slot.Unstarted -> start(best, slot, factory)
            is Slot.Running ->
                when (slot.loop.life.load()) {
                    ClientLoopLife.Alive -> slot.loop.also { it.connections.fetchAndAdd(1) }
                    ClientLoopLife.Stopped -> {
                        slots.compareAndSet(best, slot, Slot.Unstarted)
                        place(factory)
                    }
                }
        }
    }

    private fun start(
        index: Int,
        seen: Slot,
        factory: BufferFactory,
    ): Placement {
        val built = build(factory)
        if (!slots.compareAndSet(index, seen, Slot.Running(built))) {
            built.discard()
            return place(factory)
        }
        startClientLoop(built)
        built.connections.fetchAndAdd(1)
        return built
    }

    private fun build(factory: BufferFactory): ClientLoop {
        val limits = BatchLimits(maxDatagrams = RECEIVE_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val buffers = ArrayList<PlatformBuffer>(limits.maxDatagrams)
        val receive =
            ReceiveBatch(
                FixedPool(limits.maxDatagrams) { index ->
                    val buffer = factory.allocate(limits.maxDatagramSize).also(buffers::add)
                    ReceiveBuffer(index, buffer, addressOf(buffer))
                },
                limits,
            )
        val id = sequence.fetchAndAdd(1)
        return ClientLoop("$THREAD_PREFIX-$id", id, clock, receive, buffers)
    }

    /** Any thread. Stops every loop of the set: their connections end, and the set places no more. */
    fun close() {
        for (i in 0 until size) {
            when (val slot = slots.getAndSet(i, Slot.Closed)) {
                is Slot.Running -> slot.loop.close(LoopClosed { })
                Slot.Unstarted, Slot.Closed -> Unit
            }
        }
    }

    companion object {
        /** The loops every client connection shares unless it is given a set of its own. */
        val shared = ClientLoopSet(DEFAULT_CLIENT_LOOPS)

        /** The prefix of every client loop thread's name; the loop's own number follows. */
        const val THREAD_PREFIX = "quic-core-client"

        private val sequence = AtomicInt(0)
    }
}

/** The number of loops [ClientLoopSet.shared] spreads connections over. */
internal const val DEFAULT_CLIENT_LOOPS = 2

private const val RECEIVE_BATCH = 64
private const val MAX_DATAGRAM = 1500
