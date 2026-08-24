package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for #397, asserted as the universal property it actually is.
 *
 * ## The property
 *
 * **A `recv_info` / `send_info` whose owner has called `free` is memory the process can allocate
 * again.** That is the contract [QuicheApi] states for these two pairs, and every backend owes it:
 * `quiche_jni.c` honours it with `calloc`/`free`, both cinterop backends with `nativeHeap`, and the
 * FFM backend now with `calloc`/`free` as well.
 *
 * ## Why this is not scoped to the backend that was broken
 *
 * #397 was FFM-only — `recvInfoFree` and `sendInfoFree` were no-ops over two `Arena.ofAuto()` fields
 * on a process-wide singleton, so nothing was ever released. The first version of this test therefore
 * ran only under `-PquicheJvmBackend=ffm` and recorded a skip otherwise.
 *
 * That was wrong twice over. The property is not FFM-specific — a JNI backend that stopped calling
 * `free` would break it just as completely, and nothing else in the suite would notice. And the skip
 * itself was a defect: the `quiche-jvm` lanes set `SOCKET_REQUIRE_ALL_TESTS=1` precisely because they
 * build their own natives and had "inventoried zero skips", so introducing one turned two correct
 * lanes red. Asserting the property against whichever backend is loaded needs no skip, no lane
 * exemption, and covers the two backends this suite can actually reach.
 *
 * ## Why the obvious instrument is a trap
 *
 * The natural probe is a delegate that tallies `recvInfoNew`/`recvInfoFree` and asserts the counts
 * balance. [RecvSendInfoCallCounts] below is exactly that, and it is kept here as a demonstration
 * that it **cannot work**: its counts balanced perfectly against the defect, because #397 was never
 * that the frees went uncalled. It was that calling them did nothing:
 * ```
 * override fun recvInfoFree(info: QuicheRecvInfo) {
 *     // Arena.ofAuto manages lifecycle — no manual free needed
 * }
 * ```
 * A call count, by construction, cannot separate "was called" from "took effect". The counts are
 * asserted first anyway, to pin that in place so nobody re-derives it.
 *
 * ## What discriminates
 *
 * Allocate and free one struct at a time, [CYCLES] times, recording the native address each cycle
 * returns, and count how many are **distinct**:
 *
 * - a `free` that releases memory lets the next same-size allocation have the block straight back, so
 *   the distinct count collapses to a handful;
 * - a `free` that releases nothing forces every cycle to be handed fresh memory, so the distinct
 *   count is exactly [CYCLES] — it cannot be anything else, because no address is ever available.
 *
 * ```
 * FFM before the fix (Arena.ofAuto):  200 distinct of 200
 * FFM after / JNI:                      1 distinct of 200
 * ```
 *
 * Address recycling is a property of the platform allocator, not of anything in this repository, so
 * no stub, delegate or counter can satisfy it — and the test stays true if a backend's implementation
 * is rewritten, because it names the durable property rather than any implementation of it.
 *
 * Deterministic without depending on the garbage collector: pre-fix, no address was ever *available*
 * to be reused, so the outcome cannot turn on collection timing. (Confirmed separately — an
 * `Arena.ofAuto()` with every segment reference discarded and `System.gc()` forced between rounds
 * still yields 500 distinct of 500, so an auto arena releases per-arena, not per-segment.)
 *
 * ## Non-destructive
 *
 * Every handle freed here was allocated here, one at a time. Nothing owned by [loadQuicheApi]'s
 * process-wide singleton is closed, evicted or reflected on, so this is safe alongside live
 * connections in the same JVM fork.
 */
class RecvSendInfoReleaseTest {
    @Test
    fun aFreedRecvInfoIsMemoryTheAllocatorCanHandOutAgain() {
        val api = concreteApi()
        val counts = RecvSendInfoCallCounts(api)
        val bufferFactory = BufferFactory.network()

        val addresses = mutableListOf<Long>()
        repeat(CYCLES) { i ->
            // A distinct peer per cycle, matching the per-connection / per-peer-source-address shape
            // the driver and SharedQuicheServer actually allocate with.
            val from = InetSocketAddress("127.0.0.1", 40000 + i).toNativeSockAddr(bufferFactory)
            val to = InetSocketAddress("127.0.0.1", 50000 + i).toNativeSockAddr(bufferFactory)
            try {
                val info = counts.recvInfoNew(from.address, from.length, to.address, to.length)
                addresses += info.handle
                counts.recvInfoFree(info)
            } finally {
                from.free()
                to.free()
            }
        }

        assertEquals(CYCLES, counts.recvInfoNewCalls, "recvInfoNew call count")
        assertEquals(CYCLES, counts.recvInfoFreeCalls, "recvInfoFree call count")
        assertAddressesWereRecycled("recv_info", addresses, api)
    }

    @Test
    fun aFreedSendInfoIsMemoryTheAllocatorCanHandOutAgain() {
        val api = concreteApi()
        val counts = RecvSendInfoCallCounts(api)

        val addresses = mutableListOf<Long>()
        repeat(CYCLES) {
            val info = counts.sendInfoNew()
            addresses += info.handle
            counts.sendInfoFree(info)
        }

        assertEquals(CYCLES, counts.sendInfoNewCalls, "sendInfoNew call count")
        assertEquals(CYCLES, counts.sendInfoFreeCalls, "sendInfoFree call count")
        assertAddressesWereRecycled("send_info", addresses, api)
    }

    /**
     * A fresh `send_info` must read back zeroed. `quiche_jni.c` gets that from `calloc` and the FFM
     * backend used to get it from `Arena.allocate`; `sendInfoNew` hands quiche a struct whose
     * `from_len` / `to_len` are read before they are written on some paths. Guards any backend against
     * being "simplified" to a plain `malloc`.
     */
    @Test
    fun aFreshSendInfoIsZeroed() {
        val api = concreteApi()
        // Churn first, so this allocation is very likely to be recycled memory still carrying a
        // previous struct's bytes — which is exactly where a plain malloc would show up.
        repeat(CYCLES) { api.sendInfoFree(api.sendInfoNew()) }

        val info = api.sendInfoNew()
        try {
            assertEquals(0, api.sendInfoFromAddrLen(info), "a fresh send_info's from_len must be zeroed")
            assertEquals(0, api.sendInfoToAddrLen(info), "a fresh send_info's to_len must be zeroed")
        } finally {
            api.sendInfoFree(info)
        }
    }

    private fun assertAddressesWereRecycled(
        what: String,
        addresses: List<Long>,
        api: QuicheApi,
    ) {
        val distinct = addresses.distinct().size
        assertTrue(
            distinct <= RECYCLE_THRESHOLD,
            "$what memory is never released (#397): $CYCLES allocate/free cycles on " +
                "${api::class.simpleName} produced $distinct distinct native addresses (expected " +
                "<= $RECYCLE_THRESHOLD). Each cycle frees its handle before allocating the next, so a " +
                "free that released anything would let the allocator hand the same block back almost " +
                "every time. $distinct distinct addresses means the frees released nothing and every " +
                "cycle was given fresh memory — a long-running server accumulates one $what per " +
                "connection (plus one per migration, per #395) for as long as the process runs. " +
                "First 4 addresses: " + addresses.take(4).joinToString { "0x${it.toString(16)}" },
        )
    }

    /** The backend under test, seen through the recv_info guard wrapper when it is enabled. */
    private fun concreteApi(): QuicheApi {
        val api = loadQuicheApi()
        return (api as? RecvInfoGuardQuicheApi)?.delegate ?: api
    }

    private companion object {
        /**
         * Enough cycles that "every allocation was fresh" is unmistakable and a handful of distinct
         * addresses is clearly not noise, while staying instant (measured: sub-millisecond).
         */
        const val CYCLES = 200

        /** Half of [CYCLES]: far above any measured healthy value, and exactly unreachable when broken. */
        const val RECYCLE_THRESHOLD = CYCLES / 2
    }
}

/**
 * The call-counting delegate #397 proves insufficient — see [RecvSendInfoReleaseTest]'s KDoc. Kept
 * because the balanced counts it reports are the most convincing evidence that a count is the wrong
 * instrument for a `free` that is called and does nothing.
 */
private class RecvSendInfoCallCounts(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    var recvInfoNewCalls = 0
        private set
    var recvInfoFreeCalls = 0
        private set
    var sendInfoNewCalls = 0
        private set
    var sendInfoFreeCalls = 0
        private set

    override fun recvInfoNew(
        fromAddr: Long,
        fromAddrLen: Int,
        toAddr: Long,
        toAddrLen: Int,
    ): QuicheRecvInfo {
        recvInfoNewCalls++
        return delegate.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
    }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        recvInfoFreeCalls++
        delegate.recvInfoFree(info)
    }

    override fun sendInfoNew(): QuicheSendInfo {
        sendInfoNewCalls++
        return delegate.sendInfoNew()
    }

    override fun sendInfoFree(info: QuicheSendInfo) {
        sendInfoFreeCalls++
        delegate.sendInfoFree(info)
    }
}
