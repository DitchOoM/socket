package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Pooled
import com.ditchoom.socket.quic.core.pool.Taken
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes

/** Memory a datagram that arrives while a caller is inside is copied into, with the length it holds. */
@Confined
internal class CopySlot(
    index: Int,
    val bytes: NativeBytes,
) : Pooled(index) {
    var length = 0
        private set

    fun filled(length: Int) {
        this.length = length
    }
}

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
 * reaches the loop while a caller is inside is copied into a pooled slot (or, with every slot in use, into memory
 * from the caller's factory) and runs as work before that caller leaves.
 */
internal class Ingress(
    private val q: QuicheConnRef,
    private val slots: FixedPool<CopySlot>,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private val slotWork = Array(slots.size) { SlotRecv(slots[it]) }

    /** Inside: quiche reads the datagram of [length] bytes at the start of [bytes] now. */
    fun direct(
        bytes: NativeBytes,
        length: Int,
        path: SinglePath,
        now: Nanos,
        flight: ConnFlight,
    ) {
        flight.datagramIn(now, bytes, 0, length)
        q.api.connRecv(q.handle, bytes.address, length, path.recvInfo)
    }

    /** Loop thread, with a caller inside: copies the datagram into work for the thread inside. */
    fun copy(
        from: NativeBytes,
        length: Int,
    ): Copy {
        when (val slot = slots.tryTake()) {
            is Pooled -> {
                val held = slots[slot.index]
                held.bytes.copyFrom(from, 0, 0, length)
                held.filled(length)
                counts.datagramCopied()
                return slotWork[slot.index]
            }
            is Taken.Empty ->
                when (val fresh = NativeBytes.of(factory.allocate(length))) {
                    is Addressed.Native -> {
                        fresh.bytes.copyFrom(from, 0, 0, length)
                        counts.datagramCopied()
                        return FreshRecv(fresh.bytes, length)
                    }
                    is Addressed.NotAddressable -> {
                        counts.datagramDropped()
                        return Copy.Dropped
                    }
                }
        }
    }

    /** A datagram copied into a pooled slot. */
    private inner class SlotRecv(
        private val slot: CopySlot,
    ) : CopiedWork() {
        override fun run(inside: Inside) {
            try {
                inside.receiveCopy(slot.bytes, slot.length)
            } finally {
                slots.give(slot)
            }
        }

        override fun gone(end: ConnEnd) = slots.give(slot)
    }

    /** A datagram copied into memory of its own, because every slot was in use. */
    private inner class FreshRecv(
        private val bytes: NativeBytes,
        private val length: Int,
    ) : CopiedWork() {
        override fun run(inside: Inside) {
            try {
                inside.receiveCopy(bytes, length)
            } finally {
                bytes.free()
            }
        }

        override fun gone(end: ConnEnd) = bytes.free()
    }
}
