@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.loop

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.SocketRefusal
import com.ditchoom.socket.quic.core.conn.ClientSession
import com.ditchoom.socket.quic.core.conn.FlightArena
import com.ditchoom.socket.quic.core.conn.LoopMemory
import com.ditchoom.socket.quic.core.conn.SessionScratch
import com.ditchoom.socket.quic.core.observe.LoopCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.MemoryHold
import com.ditchoom.socket.quic.core.ready.NativePoller
import com.ditchoom.socket.quic.core.ready.NetStack
import com.ditchoom.socket.quic.core.ready.PollerOpened
import com.ditchoom.socket.quic.core.ready.jdkPoller
import com.ditchoom.socket.quic.core.ready.netStackOfThisRuntime
import com.ditchoom.socket.quic.core.sched.CallerSend
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.MonotonicClock
import com.ditchoom.socket.quic.core.time.QuicheTime
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBuffer
import com.ditchoom.socket.quic.core.udp.SendBatch
import kotlinx.coroutines.Runnable
import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

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
    fun place(
        factory: BufferFactory,
        api: QuicheApi,
    ): Placement {
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
            is Slot.Unstarted -> start(best, slot, factory, api)
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
                        place(factory, api)
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
        api: QuicheApi,
    ): Placement {
        val memory = LoopAllocation(factory)
        val limits = BatchLimits(maxDatagrams = RECEIVE_BATCH, maxDatagramSize = MAX_DATAGRAM)
        val buffers =
            List(limits.maxDatagrams) { ReceiveBuffer(it, memory.need(MAX_DATAGRAM) { return memory.freed(Placement.NotJdkMemory) }) }
        val sendBytes = memory.need(SEND_BYTES) { return memory.freed(Placement.NotJdkMemory) }
        val laneBytes = List(LOOP_LANES) { memory.need(LANE_BYTES) { return memory.freed(Placement.NotJdkMemory) } }
        val io =
            when (stack) {
                is NetStack.Jdk ->
                    when (val made = jdkViewsOf(stack, buffers)) {
                        is JdkViewsMade.Made -> LoopIo.Jdk(made.views)
                        is JdkViewsMade.NotJdkMemory -> return memory.freed(Placement.NotJdkMemory)
                    }
                is NetStack.Native ->
                    LoopIo.Native(
                        stack.shim,
                        memory.need(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE) { return memory.freed(Placement.NotJdkMemory) },
                        List(1 + LOOP_LANES) {
                            val datagrams = if (it == 0) SEND_LIMITS.maxDatagrams else LANE_LIMITS.maxDatagrams
                            memory.need(NativeIo.SEND_RECORD * datagrams) { return memory.freed(Placement.NotJdkMemory) }
                        },
                    )
            }
        val copies = List(LoopMemory.COPY_SLOTS) { memory.need(MAX_DATAGRAM) { return memory.freed(Placement.NotJdkMemory) } }
        val drains = List(LoopMemory.DRAIN_SLOTS) { memory.need(LoopMemory.DRAIN_BYTES) { return memory.freed(Placement.NotJdkMemory) } }
        val arena = memory.need(flightArenaBytes) { return memory.freed(Placement.NotJdkMemory) }
        val sessionBytes =
            List(ClientSession.SCRATCH_SLOTS) { memory.need(ClientSession.TICKET_BYTES) { return memory.freed(Placement.NotJdkMemory) } }
        val poller =
            when (stack) {
                is NetStack.Jdk -> jdkPoller(stack)
                is NetStack.Native ->
                    when (val opened = NativePoller.open(stack.shim, READY_EVENTS)) {
                        is PollerOpened.Opened -> opened.poller
                        is PollerOpened.Refused -> return memory.freed(Placement.PollerRefused(opened.refusal))
                    }
            }
        val out = LoopSend(SendBatch(sendBytes, SEND_LIMITS), { spareOf(factory, stack) })
        val receiveBytes = buffers.map { it.bytes }
        // The receive buffers are the loop's alone, freed when it stops; the rest is shared with its connections.
        val sharedBytes = memory.all().filter { bytes -> bytes !== sendBytes && receiveBytes.none { it === bytes } }
        val hold =
            MemoryHold(
                Runnable {
                    sharedBytes.forEach { it.free() }
                    out.free()
                },
            )
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
                        receiveBytes.forEach { it.free() }
                        hold.founding.release()
                    },
                time = QuicheTime.of(api, clock),
            )
        val running =
            RunningLoop(
                loop = loop,
                io = io,
                shared = LoopMemory(copies, drains, FlightArena(arena)),
                hold = hold,
                waits = waitsOf(stack),
                out = out,
                lanes = FixedPool(LOOP_LANES) { CallerSend(it, SendBatch(laneBytes[it], LANE_LIMITS, lane = it + 1)) },
                sessions = FixedPool(sessionBytes.size) { SessionScratch(it, sessionBytes[it]) },
            )
        if (!slots.compareAndSetAt(index, seen, Slot.Running(running))) {
            loop.close(Runnable { })
            startLoop(loop, name)
            return place(factory, api)
        }
        loop.connections.increment()
        startLoop(loop, name)
        return Placement.Placed(running)
    }

    companion object {
        /** The loops every client connection shares unless it is given a set of its own. */
        val shared = LoopSet(DEFAULT_CLIENT_LOOPS)

        /** The prefix of every client loop thread's name; the loop's own number follows. */
        const val THREAD_PREFIX = "quic-core-client"
    }
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
internal const val MAX_DATAGRAM = 1500

/** The native memory a loop starts with, freed together if the start fails part way. */
private class LoopAllocation(
    private val factory: BufferFactory,
) {
    private val made = ArrayList<NativeBytes>()

    /** [size] bytes, or [otherwise] when the factory's memory has no address. */
    inline fun need(
        size: Int,
        otherwise: () -> Nothing,
    ): NativeBytes =
        when (val a = NativeBytes.of(factory.allocate(size))) {
            is Addressed.Native -> a.bytes.also { made.add(it) }
            is Addressed.NotAddressable -> otherwise()
        }

    fun all(): List<NativeBytes> = made

    fun freed(answer: Placement): Placement {
        made.forEach { it.free() }
        made.clear()
        return answer
    }
}
