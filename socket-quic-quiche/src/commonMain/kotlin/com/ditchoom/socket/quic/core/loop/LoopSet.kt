@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.NativePoller
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.PollerOpened
import com.ditchoom.socket.quic.core.ready.jdkPoller
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/** A loop on its thread, with what its sockets receive through and the memory its connections share. */
internal class RunningLoop(
    val loop: Loop,
    val io: LoopIo,
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

    /** The native stack could not open the loop's poller. */
    class PollerRefused(
        val refusal: SocketRefusal,
    ) : Placement
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
 * that stopped is replaced by a fresh one. A loop with no connection stays parked in its poller: there are at most
 * [size] of them.
 */
@Published
internal class LoopSet(
    val size: Int,
    private val clock: LoopClock = MonotonicClock,
    /** The flight payload arena each loop's connections share. */
    private val flightArenaBytes: Int = LoopMemory.ARENA_BYTES,
    /** The stack every loop of this set and every socket on them use. */
    val stack: NetStack = netStackOfThisRuntime,
) {
    private val slots = AtomicArray(Array<Slot>(size) { Slot.Unstarted })

    /** Any thread: the loop for the next connection, counted against it, started if it had not started. */
    fun place(factory: BufferFactory): Placement {
        var best = 0
        var bestCount = 0L
        for (i in 0 until size) {
            val count =
                when (val slot = slots.loadAt(i)) {
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
        return when (val slot = slots.loadAt(best)) {
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
                        slots.compareAndSetAt(best, slot, Slot.Unstarted)
                        place(factory)
                    }
                }
        }
    }

    /** Any thread: what each started loop has done so far, for a test or a report to read. */
    fun countsOfStarted(): List<LoopCounts> =
        (0 until size).mapNotNull { i ->
            when (val slot = slots.loadAt(i)) {
                is Slot.Running -> slot.running.loop.counts
                is Slot.Unstarted, is Slot.Closed -> null
            }
        }

    /** Any thread: stops every loop; their connections end, and the set places no more. */
    fun close() {
        for (i in 0 until size) {
            when (val slot = slots.exchangeAt(i, Slot.Closed)) {
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
        val limits = BatchLimits(maxDatagrams = RECEIVE_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val buffers = ArrayList<ReceiveBuffer>(limits.maxDatagrams)
        for (i in 0 until limits.maxDatagrams) {
            when (val a = NativeBytes.of(factory.allocate(limits.maxDatagramSize))) {
                is Addressed.Native -> buffers.add(ReceiveBuffer(i, a.bytes))
                is Addressed.NotAddressable -> return freed(buffers, emptyList(), Placement.NotJdkMemory)
            }
        }
        val io =
            when (stack) {
                is NetStack.Jdk ->
                    when (val made = jdkViewsOf(stack, buffers)) {
                        is JdkViewsMade.Made -> LoopIo.Jdk(made.views)
                        is JdkViewsMade.NotJdkMemory -> return freed(buffers, emptyList(), Placement.NotJdkMemory)
                    }
                is NetStack.Native ->
                    when (val a = NativeBytes.of(factory.allocate(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE))) {
                        is Addressed.Native -> LoopIo.Native(stack.shim, a.bytes)
                        is Addressed.NotAddressable -> return freed(buffers, emptyList(), Placement.NotJdkMemory)
                    }
            }
        val poller =
            when (stack) {
                is NetStack.Jdk -> jdkPoller(clock, stack)
                is NetStack.Native ->
                    when (val opened = NativePoller.open(stack.shim, clock, READY_EVENTS)) {
                        is PollerOpened.Opened -> opened.poller
                        is PollerOpened.Refused -> return freed(buffers, ioBytes(io), Placement.PollerRefused(opened.refusal))
                    }
            }
        val sharedBytes = ArrayList<NativeBytes>(LoopMemory.COPY_SLOTS + 1)
        for (size in List(LoopMemory.COPY_SLOTS) { MAX_DATAGRAM } + flightArenaBytes) {
            when (val a = NativeBytes.of(factory.allocate(size))) {
                is Addressed.Native -> sharedBytes.add(a.bytes)
                is Addressed.NotAddressable -> {
                    poller.close()
                    return freed(buffers, sharedBytes + ioBytes(io), Placement.NotJdkMemory)
                }
            }
        }
        val shared = LoopMemory(sharedBytes.subList(0, LoopMemory.COPY_SLOTS), FlightArena(sharedBytes.last()))
        val hold = MemoryHold(Runnable { (sharedBytes + ioBytes(io)).forEach { it.free() } })
        val id = LoopIds.next()
        val name = "$THREAD_PREFIX-$id"
        val loop =
            Loop(
                id = id,
                name = name,
                poller = poller,
                clock = clock,
                receive = ReceiveBatch(FixedPool(limits.maxDatagrams) { buffers[it] }, limits),
                freeOnStop =
                    Runnable {
                        buffers.forEach { it.bytes.free() }
                        hold.release()
                    },
            )
        val running = RunningLoop(loop, io, shared, hold, waitsOf(stack))
        if (!slots.compareAndSetAt(index, seen, Slot.Running(running))) {
            loop.close(Runnable { })
            startLoop(loop, name)
            return place(factory)
        }
        loop.connections.increment()
        startLoop(loop, name)
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

private fun ioBytes(io: LoopIo): List<NativeBytes> =
    when (io) {
        is LoopIo.Jdk -> emptyList()
        is LoopIo.Native -> listOf(io.receiveRecords)
    }

/** How finely a loop on [stack] wakes on a timed wait. */
internal fun waitsOf(stack: NetStack): WaitResolution =
    when (stack) {
        is NetStack.Jdk -> stack.waits
        is NetStack.Native -> stack.waits
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

/** The ready sockets one native wait reports: as many as the loop's ready-token array holds. */
private const val READY_EVENTS = 256
private const val MAX_DATAGRAM = 1500
