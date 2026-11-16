@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.SelectorPoller
import com.ditchoom.socket.quic.core.ready.jdkStackOfThisRuntime
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.JdkBacked
import com.ditchoom.socket.quic.core.udp.JdkBytes
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import kotlinx.coroutines.Runnable
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/** A loop on its thread, with the views its sockets receive into and the memory its connections share. */
internal class RunningLoop(
    val loop: Loop,
    /** One view per receive buffer of [loop], by the buffer's index: what a JDK socket on this loop reads into. */
    val receiveViews: List<JdkBytes>,
    val shared: LoopMemory,
    /** Held by the loop until it stops and by each connection until its memory is freed; frees [shared]. */
    val hold: MemoryHold,
    /** How finely the loop's timed waits wake: what its connections pace within. */
    val waits: WaitResolution,
)

/** What [LoopSet.place] answers. */
internal sealed interface Placement {
    class Placed(
        val running: RunningLoop,
    ) : Placement

    data object SetClosed : Placement

    /** The factory makes memory a JDK socket cannot read into. */
    data object NotJdkMemory : Placement
}

private sealed interface Slot {
    data object Unstarted : Slot

    class Running(
        val running: RunningLoop,
    ) : Slot

    data object Closed : Slot
}

/**
 * [size] client loops, each started on its first connection. A new connection goes to the loop with the fewest; a loop
 * that stopped is replaced by a fresh one. A loop with no connection stays parked in its poller: it is a daemon thread
 * and there are at most [size] of them.
 */
@Published
internal class LoopSet(
    val size: Int,
    private val clock: LoopClock = MonotonicClock,
    /** The flight payload arena each loop's connections share. */
    private val flightArenaBytes: Int = LoopMemory.ARENA_BYTES,
) {
    private val slots = AtomicReferenceArray<Slot>(Array(size) { Slot.Unstarted })

    /** Any thread: the loop for the next connection, counted against it, started if it had not started. */
    fun place(factory: BufferFactory): Placement {
        var best = 0
        var bestCount = 0L
        for (i in 0 until size) {
            val count =
                when (val slot = slots.get(i)) {
                    is Slot.Unstarted -> 0L
                    is Slot.Running ->
                        slot.running.loop.connections
                            .value()
                    is Slot.Closed -> return Placement.SetClosed
                }
            if (i == 0 || count < bestCount) {
                best = i
                bestCount = count
            }
        }
        return when (val slot = slots.get(best)) {
            is Slot.Closed -> Placement.SetClosed
            is Slot.Unstarted -> start(best, slot, factory)
            is Slot.Running ->
                when (
                    slot.running.loop.life
                        .load()
                ) {
                    LoopLife.Alive -> {
                        slot.running.loop.connections
                            .increment()
                        Placement.Placed(slot.running)
                    }
                    LoopLife.Stopped -> {
                        slots.compareAndSet(best, slot, Slot.Unstarted)
                        place(factory)
                    }
                }
        }
    }

    /** Any thread: stops every loop; their connections end, and the set places no more. */
    fun close() {
        for (i in 0 until size) {
            when (val slot = slots.getAndSet(i, Slot.Closed)) {
                is Slot.Running -> slot.running.loop.close(Runnable { })
                is Slot.Unstarted, is Slot.Closed -> Unit
            }
        }
    }

    private fun start(
        index: Int,
        seen: Slot,
        factory: BufferFactory,
    ): Placement {
        val stack = jdkStackOfThisRuntime()
        val limits = BatchLimits(maxDatagrams = RECEIVE_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val buffers = ArrayList<ReceiveBuffer>(limits.maxDatagrams)
        val views = ArrayList<JdkBytes>(limits.maxDatagrams)
        for (i in 0 until limits.maxDatagrams) {
            val bytes =
                when (val a = NativeBytes.of(factory.allocate(limits.maxDatagramSize))) {
                    is Addressed.Native -> a.bytes
                    is Addressed.NotAddressable -> return freed(buffers, emptyList(), Placement.NotJdkMemory)
                }
            when (val view = JdkBytes.of(bytes)) {
                is JdkBacked.Backed -> views.add(view.bytes)
                is JdkBacked.NotJdkBacked -> {
                    bytes.free()
                    return freed(buffers, emptyList(), Placement.NotJdkMemory)
                }
            }
            buffers.add(ReceiveBuffer(i, bytes))
        }
        val sharedBytes = ArrayList<NativeBytes>(LoopMemory.COPY_SLOTS + 1)
        for (size in List(LoopMemory.COPY_SLOTS) { MAX_DATAGRAM } + flightArenaBytes) {
            when (val a = NativeBytes.of(factory.allocate(size))) {
                is Addressed.Native -> sharedBytes.add(a.bytes)
                is Addressed.NotAddressable -> return freed(buffers, sharedBytes, Placement.NotJdkMemory)
            }
        }
        val shared = LoopMemory(sharedBytes.subList(0, LoopMemory.COPY_SLOTS), FlightArena(sharedBytes.last()))
        val hold = MemoryHold(Runnable { sharedBytes.forEach { it.free() } })
        val id = LoopIds.next()
        val name = "$THREAD_PREFIX-$id"
        val loop =
            Loop(
                id = id,
                name = name,
                poller = SelectorPoller(clock, stack.scan),
                clock = clock,
                receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { buffers[it] }, limits),
                freeOnStop =
                    Runnable {
                        buffers.forEach { it.bytes.free() }
                        hold.release()
                    },
            )
        val running = RunningLoop(loop, views, shared, hold, stack.waits)
        if (!slots.compareAndSet(index, seen, Slot.Running(running))) {
            loop.close(Runnable { })
            startLoopThread(loop, name)
            return place(factory)
        }
        loop.connections.increment()
        startLoopThread(loop, name)
        return Placement.Placed(running)
    }

    private fun freed(
        buffers: List<ReceiveBuffer>,
        shared: List<NativeBytes>,
        answer: Placement,
    ): Placement {
        buffers.forEach { it.bytes.free() }
        shared.forEach { it.free() }
        return answer
    }

    companion object {
        /** The loops every client connection shares unless it is given a set of its own. */
        val shared = LoopSet(DEFAULT_CLIENT_LOOPS)

        /** The prefix of every client loop thread's name; the loop's own number follows. */
        const val THREAD_PREFIX = "quic-core-client"
    }
}

/** Numbers loops, so each loop's turns are its own in a connection's owner word. */
@Published
internal object LoopIds {
    private val sequence = AtomicInt(1)

    fun next(): Int = sequence.fetchAndIncrement()
}

/** The number of loops [LoopSet.shared] spreads connections over. */
internal const val DEFAULT_CLIENT_LOOPS = 2

private const val RECEIVE_BATCH = 64
private const val MAX_DATAGRAM = 1500
