package com.ditchoom.socket.quic

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
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
        val kind: String,
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
        record("recv-fill", addr, streamId, len, firstBytesHex)
    }

    fun recordSend(
        addr: Long,
        streamId: Long,
        len: Int,
        firstBytesHex: String,
    ) {
        record("send-enq", addr, streamId, len, firstBytesHex)
    }

    /** Destination-buffer bytes BEFORE connStreamRecv ran — [kind] names the call site. */
    fun recordRecvPre(
        kind: String,
        addr: Long,
        streamId: Long,
        firstBytesHex: String,
    ) {
        record(kind, addr, streamId, 0, firstBytesHex)
    }

    fun record(
        kind: String,
        addr: Long,
        streamId: Long,
        len: Int,
        firstBytesHex: String,
    ) {
        val seq = cursor.addAndFetch(1L)
        ring[(seq % CAP).toInt()] = Fill(kind, addr, streamId, len, firstBytesHex, seq)
    }

    /**
     * Every recorded event for [streamId], oldest to newest. Both connections of an in-process
     * echo pair share one stream id (QUIC stream ids are connection-scoped and equal at both
     * ends), so this is the full chain: client send-enq -> server recv-fill -> server send-enq
     * (echo) -> client recv-fill. The first link whose bytes are garbage names the corrupting
     * segment.
     */
    fun history(streamId: Long): String {
        val top = cursor.load()
        val floor = maxOf(1L, top - CAP + 1)
        val out = StringBuilder()
        var i = floor
        while (i <= top) {
            val f = ring[(i % CAP).toInt()]
            if (f != null && f.streamId == streamId && f.seq == i) {
                out.append("  [seq=${f.seq}] ${f.kind} addr=0x${f.addr.toString(16)} len=${f.len}: ${f.firstBytesHex}\n")
            }
            i++
        }
        return if (out.isEmpty()) "  (no events for stream=$streamId; ring top=$top)" else out.toString().trimEnd()
    }

    /** The most recent fill recorded for [addr], scanning newest-first. */
    fun describe(addr: Long): String {
        val top = cursor.load()
        var i = top
        val floor = maxOf(1L, top - CAP + 1)
        while (i >= floor) {
            val f = ring[(i % CAP).toInt()]
            if (f != null && f.addr == addr && f.kind == "recv-fill") {
                return "fill(addr=0x${addr.toString(16)} stream=${f.streamId} len=${f.len} seq=${f.seq} of ${top}): ${f.firstBytesHex}"
            }
            i--
        }
        return "no fill recorded for addr=0x${addr.toString(16)} (ring top=$top)"
    }

    // ---- per-connection thread census (round 9) -------------------------------------------------
    // quiche conns are single-threaded by contract; a conn touched by two threads with overlapping
    // liveness is Rust UB through the FFI. First-occurrence-only COW writes keep the hot path free.

    private val connThreads = AtomicReference<Map<Long, Map<String, Set<String>>>>(emptyMap())

    fun recordThread(
        connHandle: Long,
        threadName: String,
        method: String,
    ) {
        while (true) {
            val cur = connThreads.load()
            val perConn = cur[connHandle] ?: emptyMap()
            val methods = perConn[threadName] ?: emptySet()
            if (method in methods) return
            val next = cur + (connHandle to (perConn + (threadName to (methods + method))))
            if (connThreads.compareAndSet(cur, next)) return
        }
    }

    /** Connections touched by more than one distinct thread, with the methods each thread used. */
    fun threadReport(): String {
        val snap = connThreads.load()
        val bad = snap.filterValues { it.size > 1 }
        if (bad.isEmpty()) return "  (every conn single-threaded: ${snap.size} conns censused)"
        return bad.entries.joinToString("\n") { (h, per) ->
            "  conn=0x${h.toString(16)} touched by ${per.size} threads:\n" +
                per.entries.joinToString("\n") { (t, ms) -> "    [$t] ${ms.sorted().joinToString(", ")}" }
        }
    }
}
