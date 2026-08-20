package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * DEBUG-ONLY (branch debug/401-soak-hunt, never merges): fill-time evidence for the #401 corrupt
 * echo. The quiche driver records the first bytes of every stream-read chunk at the moment quiche
 * delivered it; the soak suite's corruption report then compares that snapshot against what the
 * consumer actually read. Fill == consumed-garbage → the bytes came OUT of quiche (sender/wire
 * side). Fill == expected payload → the buffer was mutated between fill and consume (receiver-side
 * lifecycle). Content-neutral on purpose — MALLOC_PERTURB_ suppressed the repro entirely, so the
 * probe must not touch allocator behavior.
 *
 * A fixed ring, not a growing map: pool addresses recycle heavily and the exact-race debug context
 * tolerates the ring's benign data races (JVM reference writes are atomic; this branch only ever
 * runs :socket-quic-quiche:jvmTest).
 */
@OptIn(ExperimentalAtomicApi::class)
object Probe401 {
    class Fill(
        val addr: Long,
        val streamId: Long,
        val len: Int,
        val firstBytesHex: String,
        val seq: Long,
    )

    private const val CAP = 8192
    private val ring = arrayOfNulls<Fill>(CAP)
    private val cursor = AtomicLong(0L)

    fun recordFill(
        addr: Long,
        streamId: Long,
        len: Int,
        firstBytesHex: String,
    ) {
        val seq = cursor.addAndFetch(1L)
        ring[(seq % CAP).toInt()] = Fill(addr, streamId, len, firstBytesHex, seq)
    }

    /** The most recent fill recorded for [addr], scanning newest-first. */
    fun describe(addr: Long): String {
        val top = cursor.load()
        var i = top
        val floor = maxOf(1L, top - CAP + 1)
        while (i >= floor) {
            val f = ring[(i % CAP).toInt()]
            if (f != null && f.addr == addr) {
                return "fill(addr=0x${addr.toString(16)} stream=${f.streamId} len=${f.len} seq=${f.seq} of ${top}): ${f.firstBytesHex}"
            }
            i--
        }
        return "no fill recorded for addr=0x${addr.toString(16)} (ring top=$top)"
    }
}
