package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for #397: on the JDK 21+ FFM backend `recvInfoFree` / `sendInfoFree` released
 * nothing, so every connection's `quiche_recv_info` and `quiche_send_info` accumulated for the life
 * of the process.
 *
 * ## The property under test
 *
 * **A `recv_info` / `send_info` whose owner has called `free` is memory the process can allocate
 * again.** That is the whole contract, and it is the one every other backend already honours —
 * `quiche_jni.c` with `calloc`/`free`, both cinterop backends with `nativeHeap`.
 *
 * ## Why the obvious test is a trap, and why this one is not
 *
 * The natural instrument is a [QuicheApi] delegate that tallies `recvInfoNew`/`recvInfoFree` and
 * asserts the counts balance. [RecvSendInfoCallCounts] below is exactly that delegate, and it is
 * kept here deliberately — as a **demonstration that it cannot work**. Its counts balanced perfectly
 * against the defect, because #397 was never that the frees went uncalled. It was that calling them
 * did nothing:
 * ```
 * override fun recvInfoFree(info: QuicheRecvInfo) {
 *     // Arena.ofAuto manages lifecycle — no manual free needed
 * }
 * ```
 * A call count, by construction, cannot tell "was called" from "took effect". So the counts are
 * asserted first — to pin the trap in place, so nobody re-derives it — and then the real assertion
 * runs.
 *
 * ## The real assertion: the allocator recycles the address
 *
 * Allocate and free one `recv_info` at a time, [CYCLES] times, recording the native address each
 * cycle hands back. The discriminator is how many **distinct** addresses that produces:
 *
 * - If `free` releases the memory, the very next `calloc` of the same size is overwhelmingly likely
 *   to be handed the block just released, so the distinct count collapses to a handful.
 * - If `free` releases nothing, every cycle must be handed *fresh* memory, so the distinct count is
 *   exactly [CYCLES] — it cannot be anything else, because no address is ever available for reuse.
 *
 * Measured on this JDK before and after the fix, with the pre-fix behaviour modelled directly
 * (`Arena.ofAuto()`, segment references discarded, `System.gc()` forced between rounds — 500 of 500
 * distinct, confirming an auto arena held by a process-wide singleton releases nothing per-segment):
 * ```
 *   leaking (Arena.ofAuto):   200 distinct of 200      <- pre-fix, every run
 *   released (calloc/free):     1 distinct of 200      <- post-fix
 * ```
 * The threshold below sits at half of [CYCLES] — a ~100x margin over the measured post-fix value and
 * exactly unreachable pre-fix. It is asserted as an inequality rather than an exact count because
 * the number of distinct addresses a working allocator produces is an allocator detail; that it is
 * *bounded well below the cycle count* is the property, and "every allocation was fresh" is the
 * defect.
 *
 * ## Why this does not name the code under test
 *
 * Address recycling is a property of the platform allocator, not of anything in this repository. No
 * stub, delegate or counter can satisfy it — a `recvInfoFree` that does nothing produces
 * [CYCLES] distinct addresses no matter how it is written or instrumented. The test therefore stays
 * true if the fix is rewritten (per-allocation `calloc`/`free`, a closeable per-connection arena, a
 * slab, anything), and goes red the moment releasing stops happening. It asserts the durable
 * property, not this implementation of it.
 *
 * ## Non-destructive
 *
 * Every handle this test frees is one this test allocated, in the same method, one at a time. It
 * closes, evicts or reflects on nothing owned by [loadQuicheApi]'s process-wide singleton, so it is
 * safe alongside live connections in the same JVM fork. (An earlier draft of the #397 red test
 * called `.close()` on the singleton's own arena — harmless only by the accident that `ofAuto`
 * refuses; against a fix that kept a closeable arena on the singleton it would have freed memory out
 * from under live connections.)
 *
 * ## FFM only, and it says so out loud
 *
 * `:socket-quic-quiche:jvmTest` runs on three CI legs — JNI, FFM, and a JDK-17-launcher JNI — and
 * #397 never applied to JNI. Asserting unconditionally would make two correct legs permanently red;
 * passing quietly on them would be a lane reporting PASS having exercised nothing, which is #399.
 * So this reads the same `quiche.expectedJvmBackend` property [JvmQuicheBackendIdentityTest] asserts
 * against, records a visible skip on the non-FFM legs, and on the FFM leg first proves the runtime
 * really loaded FFM before testing anything.
 */
class FfmRecvSendInfoReleaseTest {
    @Test
    fun aFreedRecvInfoIsMemoryTheAllocatorCanHandOutAgain() {
        val api = ffmApiOrSkip() ?: return
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
        assertAddressesWereRecycled("recv_info", addresses)
    }

    @Test
    fun aFreedSendInfoIsMemoryTheAllocatorCanHandOutAgain() {
        val api = ffmApiOrSkip() ?: return
        val counts = RecvSendInfoCallCounts(api)

        val addresses = mutableListOf<Long>()
        repeat(CYCLES) {
            val info = counts.sendInfoNew()
            addresses += info.handle
            counts.sendInfoFree(info)
        }

        assertEquals(CYCLES, counts.sendInfoNewCalls, "sendInfoNew call count")
        assertEquals(CYCLES, counts.sendInfoFreeCalls, "sendInfoFree call count")
        assertAddressesWereRecycled("send_info", addresses)
    }

    /**
     * A fresh `send_info` must read back as zeroed. The pre-#397 implementation got this from
     * `Arena.allocate`, which zero-fills; any replacement has to keep it, because `sendInfoNew`
     * hands quiche a struct whose `from_len` / `to_len` are read before they are written on some
     * paths. Guards the fix against being "simplified" to a plain `malloc`.
     */
    @Test
    fun aFreshSendInfoIsZeroed() {
        val api = ffmApiOrSkip() ?: return
        // Churn first, so this allocation is very likely to be recycled memory carrying a previous
        // struct's bytes — an uninitialised allocator block, which is where a plain malloc shows up.
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
    ) {
        val distinct = addresses.distinct().size
        assertTrue(
            distinct <= RECYCLE_THRESHOLD,
            "$what memory is never released (#397): $CYCLES allocate/free cycles produced $distinct " +
                "distinct native addresses (expected <= $RECYCLE_THRESHOLD). Each cycle frees its " +
                "handle before allocating the next, so a `free` that released anything would let the " +
                "allocator hand the same block back almost every time. $distinct distinct addresses " +
                "means the frees released nothing and each cycle was given fresh memory — a " +
                "long-running JDK 21+ FFM server accumulates one $what per connection (plus one per " +
                "migration, per #395) for as long as the process runs. " +
                "First 4 addresses: " + addresses.take(4).joinToString { "0x${it.toString(16)}" },
        )
    }

    /**
     * The concrete FFM [QuicheApi], or `null` after recording a visible skip when this run is not an
     * FFM run. Fails loudly — rather than skipping — when the build asked for FFM but the runtime
     * loaded something else, since that is #399 wearing this test's clothes.
     */
    private fun ffmApiOrSkip(): QuicheApi? {
        val expected = System.getProperty(EXPECTED_BACKEND_PROPERTY)
        if (expected != "ffm") {
            recordSkip(
                FfmRecvSendInfoReleaseTest::class,
                SkipReason.OptInLaneNotRequested(
                    "#397 is FFM-only — JNI frees recv_info/send_info correctly via calloc/free. " +
                        "This run's $EXPECTED_BACKEND_PROPERTY was '$expected', not 'ffm'. Run with " +
                        "-PquicheJvmBackend=ffm to exercise it.",
                ),
            )
            assumeTrue("FFM backend not requested for this run — see the recorded skip above", false)
            return null
        }
        val concrete = (loadQuicheApi() as? RecvInfoGuardQuicheApi)?.delegate ?: loadQuicheApi()
        assertEquals(
            "FfmQuicheApi",
            concrete::class.simpleName,
            "The build asked for the FFM backend ($EXPECTED_BACKEND_PROPERTY=ffm) but the runtime " +
                "loaded '${concrete::class.simpleName}'. The java21 classpath shadowing has stopped " +
                "taking effect (#399), so #397 was not exercised.",
        )
        return concrete
    }

    private companion object {
        const val EXPECTED_BACKEND_PROPERTY = "quiche.expectedJvmBackend"

        /**
         * Enough cycles that "every allocation was fresh" is unmistakable and a handful of distinct
         * addresses is clearly not noise, while staying instant (measured: sub-millisecond).
         */
        const val CYCLES = 200

        /** Half of [CYCLES]: ~100x above the measured post-fix value, exactly unreachable pre-fix. */
        const val RECYCLE_THRESHOLD = CYCLES / 2
    }
}

/**
 * The call-counting delegate #397 proves insufficient — see [FfmRecvSendInfoReleaseTest]'s KDoc.
 * Kept because the balanced counts it reports are the most convincing evidence that a count is the
 * wrong instrument for a `free` that is called and does nothing.
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
