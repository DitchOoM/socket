package com.ditchoom.socket.quic.core.udp

import com.ditchoom.socket.quic.core.observe.LoopOnly
import com.ditchoom.socket.quic.core.pool.FixedPool
import com.ditchoom.socket.quic.core.pool.Taken

/**
 * The receive slots of one loop's [ReceiveBatch]: each holds a pool buffer whose address is already written in the
 * `qnet` receive records, and keeps it across receives. A receive that [handUp]s `k` datagrams leaves the other slots
 * armed, so the next [arm] takes `k` buffers from the pool and no more. A slot is a pool buffer or [Taken.Empty], and a
 * buffer is in exactly one place: its slot, the batch it was handed up to, or the pool.
 */
@LoopOnly
internal class ReceiveSlots(
    private val pool: FixedPool<ReceiveBuffer>,
    private val capacity: Int,
) {
    private val slots = Array<Taken>(capacity) { Taken.Empty }

    /** Slots below this index may be empty; every slot from it up is armed. */
    private var open = capacity

    private var written: Written = Written.Nowhere

    /**
     * Arms every empty slot the pool can fill, writing each address into [records], and answers how many slots from the
     * first are armed: a receive is given that many records, and datagram `i` lands in slot `i`.
     */
    fun arm(records: NativeBytes): Int {
        if (!isWrittenIn(records)) rewrite(records)
        var i = 0
        while (i < open) {
            if (slots[i] !is ReceiveBuffer) {
                val taken = pool.tryTake()
                if (taken !is ReceiveBuffer) return i
                slots[i] = taken
                records.putLong(i * NativeIo.RECV_RECORD, taken.bytes.address)
            }
            i++
        }
        open = 0
        return capacity
    }

    /** The buffer of slot [i], which a receive filled: it leaves the slot, and the next [arm] refills it. */
    fun handUp(i: Int): Taken {
        val buffer = slots[i]
        slots[i] = Taken.Empty
        if (i >= open) open = i + 1
        return buffer
    }

    /** Gives every armed buffer back to the pool, once; the slots are empty and the next [arm] takes again. */
    fun disarm() {
        for (i in 0 until capacity) {
            val buffer = slots[i]
            if (buffer is ReceiveBuffer) pool.give(buffer)
            slots[i] = Taken.Empty
        }
        open = capacity
    }

    /** Armed slots now: diagnostics and tests. */
    fun armed(): Int = slots.count { it is ReceiveBuffer }

    /** A different block of records than the one the addresses went into: writes the armed slots' addresses there. */
    private fun rewrite(records: NativeBytes) {
        for (i in 0 until capacity) {
            val buffer = slots[i]
            if (buffer is ReceiveBuffer) records.putLong(i * NativeIo.RECV_RECORD, buffer.bytes.address)
        }
        written = Written.In(records)
    }

    private fun isWrittenIn(records: NativeBytes): Boolean =
        when (val last = written) {
            is Written.In -> last.records === records
            is Written.Nowhere -> false
        }

    private sealed interface Written {
        data object Nowhere : Written

        class In(
            val records: NativeBytes,
        ) : Written
    }
}
