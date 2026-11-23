@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch

/** Memory a datagram that arrives while a caller is inside is copied into, with its length and where it came from. */
@Confined
internal class CopySlot(
    index: Int,
    val bytes: NativeBytes,
) : Pooled(index) {
    var length = 0
        private set
    var from = NO_SOURCE
        private set

    fun filled(
        length: Int,
        from: PathKey,
    ) {
        this.length = length
        this.from = from
    }
}

/** How the loop gives quiche a datagram: [Direct]ly, holding the connection, or as a [Copy] run inside, in order. */
internal enum class Reach { Direct, Copy }

/** What [Ingress.copy] made: work that hands the copy to quiche from inside, or nothing (no memory was free). */
internal sealed interface Copy {
    data object Dropped : Copy
}

/** Work that hands a copied datagram to quiche from inside. */
internal sealed class CopiedWork :
    ConnWork(),
    Copy

/**
 * Datagrams in: the only file that hands quiche a datagram. The thread inside hands it over at once; a datagram that
 * reaches the loop while a caller is inside is copied into a slot its loop's connections share (or, with every slot
 * in use, into memory from the caller's factory) and runs as work, on that caller before it leaves or else on the loop.
 * While a copy waits to run, every later datagram is copied behind it, so quiche reads them in the order they arrived.
 */
@Published
internal class Ingress(
    private val q: QuicheConnRef,
    private val shared: LoopMemory,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
    private val clock: LoopClock,
) {
    /** Copies made and not yet read by quiche or dropped: the loop makes them, the thread inside runs them. */
    private val queued = AtomicInt(0)

    /**
     * Loop thread, with [hold] as the loop has the connection: a datagram goes to quiche directly only when the loop holds
     * it and no copy made earlier still waits to be read; otherwise it is copied behind.
     */
    fun reach(hold: LoopHold): Reach =
        when (hold) {
            LoopHold.In -> if (queued.load() > 0) Reach.Copy else Reach.Direct
            LoopHold.Out -> Reach.Copy
        }

    /** Inside: a copy was read by quiche. */
    fun copyRead() {
        queued.decrementAndFetch()
    }

    /** Inside, as the connection ends: a queued copy is let go unread. */
    fun copyGone() {
        queued.decrementAndFetch()
    }

    /** Loop thread: a copy the work queue refused, the connection having ended, is dropped unread and counted. */
    fun copyRefused() {
        queued.decrementAndFetch()
        counts.datagramDropped()
    }

    /**
     * Inside: quiche reads the datagram of [length] bytes at the start of [bytes] now, as [info] describes it. It is
     * recorded at the time quiche reads it, which its acknowledgements' round trips are measured to, read from [clock]: a
     * slow host reaches quiche well after its socket read, and a replay delivers the datagram at the recorded time.
     */
    fun direct(
        bytes: NativeBytes,
        length: Int,
        info: QuicheRecvInfo,
        flight: ConnFlight,
    ) {
        flight.datagramIn(clock.now(), bytes, 0, length)
        q.api.connRecv(q.handle, bytes.address, length, info)
    }

    /** Inside: a datagram of [length] bytes [from] an address the connection does not follow is dropped, and recorded. */
    fun refused(
        length: Int,
        from: PathKey,
        flight: ConnFlight,
    ) {
        counts.datagramDropped()
        flight.dropped(clock.now(), DropWhy.TooManyPeerAddresses, length, from.family, from.port, from.hi, from.lo)
    }

    /** Loop thread, when [reach] says so: copies the datagram that came [from] into work for the thread inside. */
    fun copy(
        bytes: NativeBytes,
        length: Int,
        from: PathKey,
    ): Copy {
        when (val slot = shared.slots.tryTake()) {
            is Pooled -> {
                val held = shared.slots[slot.index]
                held.bytes.copyFrom(bytes, 0, 0, length)
                held.filled(length, from)
                counts.datagramCopied()
                queued.incrementAndFetch()
                return shared.workFor(slot.index)
            }
            is Taken.Empty ->
                when (val fresh = NativeBytes.of(factory.allocate(length))) {
                    is Addressed.Native -> {
                        fresh.bytes.copyFrom(bytes, 0, 0, length)
                        counts.datagramCopied()
                        queued.incrementAndFetch()
                        return FreshRecv(fresh.bytes, length, from)
                    }
                    is Addressed.NotAddressable -> {
                        counts.datagramDropped()
                        return Copy.Dropped
                    }
                }
        }
    }

    /** A datagram copied into memory of its own, because every slot was in use. */
    private inner class FreshRecv(
        private val bytes: NativeBytes,
        private val length: Int,
        private val from: PathKey,
    ) : CopiedWork() {
        override fun run(inside: Inside) {
            try {
                inside.receiveCopy(bytes, length, from)
            } finally {
                bytes.free()
            }
        }

        override fun gone(end: ConnEnd) = bytes.free()
    }
}

/** A datagram copied into a shared slot: whichever connection took the slot runs it, then gives the slot back. */
internal class SlotRecv(
    private val slots: FixedPool<CopySlot>,
    private val slot: CopySlot,
) : CopiedWork() {
    override fun run(inside: Inside) {
        try {
            inside.receiveCopy(slot.bytes, slot.length, slot.from)
        } finally {
            slots.give(slot)
        }
    }

    override fun gone(end: ConnEnd) = slots.give(slot)
}

/** What a copy slot says it came from before it holds a datagram: no address. */
private val NO_SOURCE = PathKey(family = 0, port = 0, hi = 0L, lo = 0L)
