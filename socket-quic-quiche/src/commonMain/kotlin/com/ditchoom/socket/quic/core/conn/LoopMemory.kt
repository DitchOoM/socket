@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.stream.DrainSlot
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The memory every connection on one loop shares, so a connection holds none of its own for datagrams: the slots a
 * datagram is copied into while a caller is inside its connection, the slots stream data nobody reads is dropped into,
 * and the flight recorders' payload arena. Sized per loop, not per connection: a loop copies or records only what it
 * carries, whatever the number of connections.
 */
internal class LoopMemory(
    copies: List<NativeBytes>,
    drains: List<NativeBytes>,
    val arena: FlightArena,
) {
    val slots = FixedPool(copies.size) { CopySlot(it, copies[it]) }

    val drains = FixedPool(drains.size) { DrainSlot(it, drains[it].address, drains[it].capacity) }

    /** Each slot's work, made once: a slot is in at most one connection's inbox at a time. */
    private val slotWork = Array(slots.size) { SlotRecv(slots, slots[it]) }

    /** The work that hands the datagram in slot [index] to quiche, inside whichever connection took the slot. */
    fun workFor(index: Int): SlotRecv = slotWork[index]

    companion object {
        /** Copy slots per loop: callers inside connections of one loop at once, each with datagrams arriving. */
        const val COPY_SLOTS = 64

        /** Drain slots per loop: callers inside connections of one loop at once, each dropping a stream's data. */
        const val DRAIN_SLOTS = 4

        /** A drain slot's size: a drain reads as many times as it takes. */
        const val DRAIN_BYTES = 4 * 1024

        /** Flight payload per loop: a walk's handshakes on a client loop, the recent datagrams on a server's. */
        const val ARENA_BYTES = 256 * 1024
    }
}

/** What [FlightArena.hex] answers. */
internal sealed interface ArenaRead {
    class Hex(
        val text: String,
    ) : ArenaRead

    /** Later writes reached the payload: the record keeps its length and time, not its bytes. */
    data object Overwritten : ArenaRead
}

/**
 * Datagram payload the flight recorders of one loop's connections write into, as a ring. A writer reserves its bytes
 * with one atomic add, so threads inside different connections write at once without waiting; a record keeps the
 * absolute position its payload starts at, and a payload later writes reached reads as [ArenaRead.Overwritten].
 */
@Published
internal class FlightArena(
    private val bytes: NativeBytes,
) {
    private val written = AtomicLong(0L)
    val capacity: Int get() = bytes.capacity

    /** Copies at most [capacity] bytes of the datagram; answers the absolute position they start at. */
    fun write(
        from: NativeBytes,
        offset: Int,
        length: Int,
    ): Long {
        val kept = minOf(length, capacity)
        val at = written.fetchAndAdd(kept.toLong())
        val start = (at % capacity).toInt()
        val first = minOf(kept, capacity - start)
        bytes.copyFrom(from, offset, start, first)
        if (first < kept) bytes.copyFrom(from, offset + first, 0, kept - first)
        return at
    }

    /** The payload of [length] bytes written at [at], read before any later write reached it, or that one did. */
    fun hex(
        at: Long,
        length: Int,
    ): ArenaRead {
        if (overwritten(at)) return ArenaRead.Overwritten
        val kept = minOf(length, capacity)
        val out = StringBuilder(kept * 2)
        for (k in 0 until kept) {
            val b = bytes.byteAt(((at + k) % capacity).toInt())
            out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return if (overwritten(at)) ArenaRead.Overwritten else ArenaRead.Hex(out.toString())
    }

    private fun overwritten(at: Long): Boolean = written.load() > at + capacity

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
