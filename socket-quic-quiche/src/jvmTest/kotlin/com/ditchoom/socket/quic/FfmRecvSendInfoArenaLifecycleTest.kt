package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import org.junit.Assume.assumeTrue
import java.lang.foreign.Arena
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * RED regression test for #397: on the JDK 21+ FFM backend, `FfmQuicheApi.recvInfoFree` and
 * `sendInfoFree` are no-ops, so every connection's `quiche_recv_info` / `quiche_send_info`
 * accumulates for the life of the process.
 *
 * ## Why the obvious test is a trap
 *
 * The natural instrument here is a [QuicheApi] delegate that tallies `recvInfoNew`/`recvInfoFree`
 * and `sendInfoNew`/`sendInfoFree` calls, mirroring [CidAuditQuicheApi] in `MigrationSim.kt`, and
 * asserting the counts balance after N connections' worth of recv_info/send_info have been opened
 * and closed. [RecvSendInfoCallCounts] below is exactly that delegate, and its counts DO balance —
 * every `recvInfoNew` this test makes is followed by a `recvInfoFree`, every `sendInfoNew` by a
 * `sendInfoFree`. That is the trap: #397 is not that the frees go uncalled, it is that calling them
 * does nothing. `FfmQuicheApi.recvInfoFree`/`sendInfoFree` are, verbatim:
 * ```
 * override fun recvInfoFree(info: QuicheRecvInfo) {
 *     // Arena.ofAuto manages lifecycle — no manual free needed
 * }
 * ```
 * A call-counting delegate happily reports 1:1 balance against that no-op. Proving the defect
 * requires an assertion that distinguishes "free was called" from "the memory was actually
 * released" — which a call count, by construction, cannot do.
 *
 * ## What this test asserts instead
 *
 * `recvInfoNew`/`sendInfoNew` allocate from `FfmQuicheApi.recvInfoArena` / `.sendInfoArena` — two
 * `Arena.ofAuto()` instance fields on `FfmQuicheApi`, which has been a deliberate **process-wide
 * singleton** since #202 (dlclose-safety: BoringSSL's pthread TLS destructors outlive an unloadable
 * arena). `Arena.ofAuto()` is a fundamentally different *kind* of arena from the
 * `Arena.ofConfined()`/`.ofShared()` the issue names as the fix: an auto arena has no owning thread
 * and cannot be closed programmatically — it only releases memory once the `Arena` object itself
 * becomes GC-unreachable, which, held as a field on a process-lifetime singleton, it never is. A
 * per-connection confined/shared arena, by contrast, IS closeable, and closing it on connection
 * teardown is exactly what would fix #397. So the honest question is "which *kind* of arena is
 * this", asked without touching anything the production singleton owns.
 *
 * That question has a non-obvious answer path. The natural probe —
 * `arena.javaClass == Arena.ofAuto().javaClass` — does NOT work: verified empirically (see the
 * throwaway program referenced below) that `Arena.ofAuto()`, `.ofConfined()`, and `.ofShared()` all
 * return the *same* concrete wrapper class on this JDK (`jdk.internal.foreign.MemorySessionImpl$1`,
 * an anonymous `Arena` adapter). What DOES differ is the `MemorySegment.Scope` object each arena's
 * public `scope()` accessor returns: `Arena.ofAuto().scope()` is a `jdk.internal.foreign.
 * ImplicitSession`, `.ofConfined().scope()` a `ConfinedSession`, `.ofShared().scope()` a
 * `SharedSession` — three distinct classes, reachable through nothing but the public `Arena.scope()`
 * method, so no `setAccessible`/module-opens issue. (Reflecting one level deeper — into the
 * `MemorySessionImpl` a `MemorySessionImpl$1` wraps — WAS tried and does NOT work: `jdk.internal.
 * foreign` is not opened by `java.base`, so `Field.setAccessible(true)` on that wrapped field throws
 * `InaccessibleObjectException: module java.base does not "opens jdk.internal.foreign"...` without
 * `--add-opens`, which this test does not have and should not need.)
 *
 * So the assertion is: reflect `FfmQuicheApi`'s `recvInfoArena`/`sendInfoArena` fields (`private
 * val`, ordinary Kotlin fields on our own code — not a JDK-internal type, no module issue), read
 * `.scope().javaClass` off each, and assert it differs from a **locally created, disposable**
 * `Arena.ofAuto().scope().javaClass`. Today it does not differ — the production field's scope class
 * IS `ImplicitSession`, because the field genuinely is `Arena.ofAuto()` — so the assertion fails,
 * correctly.
 *
 * ## Why not `.close()`
 *
 * An earlier version of this test called `.close()` directly on the reflected production arena and
 * asserted it didn't throw. That is destructive against any fix that keeps the arena as a singleton
 * field but simply swaps its kind (`Arena.ofConfined()`/`.ofShared()`, still one field, now
 * closeable) — such a fix is entirely plausible and would make that version of this test actually
 * close a live, shared arena mid-suite, in the same JVM fork as every other test, while connections
 * elsewhere in that fork are still using memory it owns. A regression test that becomes a corruption
 * source the moment the defect it targets is fixed is worse than no test.
 *
 * This version never calls `.close()` on anything reached through [loadQuicheApi]. The only arenas
 * closed here are the local `Arena.ofConfined()`/`.ofShared()` references this test creates purely
 * to read their `.scope().javaClass` for the failure message — disposable, owned end-to-end by this
 * test method, closed by it, and never shared with anything else in the process. `.scope()` itself
 * is a pure accessor (no allocation, no lifecycle effect), so reading it off the production field is
 * as safe as reading any other property.
 *
 * ## Deterministic, no GC dependency
 *
 * An arena's session class is fixed at the moment `Arena.ofAuto()`/`.ofConfined()`/`.ofShared()`
 * constructs it — it is a property of *which factory method built it*, not of how much has been
 * allocated from it, whether anything is currently reachable, or whether a collection has run.
 * Driving N connections' worth of recv_info/send_info through the real API first (rather than
 * inspecting a bare arena) ties the assertion to the actual usage pattern the issue describes, but
 * the pass/fail outcome does not depend on N, on timing, or on the garbage collector ever running.
 *
 * ## FFM, not JNI
 *
 * `:socket-quic-quiche:jvmTest` defaults to the JNI backend (JNI frees `recv_info`/`send_info`
 * correctly via `quiche_jni.c`'s `calloc`/`free` — #397 does not reproduce there), and CI's
 * `quiche-jvm` job runs this same full suite on THREE legs (JNI, FFM, JDK17-launcher JNI) — only one
 * of which passes `-PquicheJvmBackend=ffm`. So this test cannot simply assert "the backend is ffm"
 * unconditionally: on the two JNI legs that would turn a correctly-behaving backend into a permanent
 * false failure, which is its own way of making a lane's signal worthless. Instead it reads the same
 * `quiche.expectedJvmBackend` system property [JvmQuicheBackendIdentityTest] (#399) does, and:
 *  - when the build did not ask for FFM, records an [SkipReason.OptInLaneNotRequested] skip and
 *    stops — visible in the CI skip inventory, never a silent pass, never a false failure on the
 *    legs #397 does not apply to;
 *  - when the build DID ask for FFM (`-PquicheJvmBackend=ffm`), asserts the runtime actually loaded
 *    it, using the same delegate-unwrapping idiom as [JvmQuicheBackendIdentityTest] — so a run that
 *    silently fell back to JNI fails loudly here instead of reporting a false green, and only then
 *    does the real #397 assertion below run.
 *
 * ## This test does not go green on its own
 *
 * The assertion is reached by reflecting `recvInfoArena`/`sendInfoArena` — `private val` fields on
 * `FfmQuicheApi`, named exactly as they are today. The durable property this test is standing in for
 * is: **recv_info and send_info allocations are released when the connection that owns them is torn
 * down.** A fix that satisfies that property is not obligated to keep those two fields, under those
 * names, on the singleton — the issue itself suggests the fix moves the allocation to a
 * per-connection arena, which cannot honestly live as a field on a process-wide object. If it moves,
 * `getDeclaredField("recvInfoArena")` throws `NoSuchFieldException` and this test FAILS — which will
 * look like a regression from whoever is watching CI, when it is actually the fix landing. Whoever
 * closes #397 should expect to delete or rewrite this test as part of that change, not be alarmed
 * that it went red a different way.
 *
 * The ideal test would not reflect on implementation fields at all: it would take the actual
 * `MemorySegment` `recvInfoNew`/`sendInfoNew` allocate, drive the connection that owns it through a
 * real teardown, and then assert that specific segment's own `.scope().isAlive()` reads `false` —
 * proving the *allocation this test made*, not a proxy for "the arena's kind", was released. That is
 * not achievable today, confirmed by reading the production code this test cannot change:
 * `QuicheRecvInfo`/`QuicheSendInfo` ([QuicheHandles.kt]) are `@JvmInline value class`es wrapping a
 * single raw `Long` address, and `FfmQuicheApi.recvInfoNew`/`.sendInfoNew` return
 * `QuicheRecvInfo(info.address())`/`QuicheSendInfo(info.address())` — the `MemorySegment` local
 * variable (`info`) that `arena.allocate(...)` returned is never stored anywhere and never escapes
 * the function. [QuicheApi]'s own top-of-file KDoc states the design intent directly: "All data
 * passes as native addresses — no byte array copies anywhere." That is deliberate zero-copy shape
 * for the hot path, and it is exactly what makes per-allocation scope inspection impossible from
 * outside `FfmQuicheApi`: there is no live `MemorySegment` reference anywhere a caller — or a test —
 * can reach once `recvInfoNew`/`sendInfoNew` has returned. Getting the ideal test would itself
 * require a production change (retaining or exposing the segment/scope per handle), which is out of
 * scope for a test-only file.
 *
 * ## A failure here means
 *
 * `FfmQuicheApi`'s recv_info/send_info fields are still the process-wide `Arena.ofAuto()` kind that
 * cannot deterministically release anything short of the whole singleton becoming unreachable — i.e.
 * a long-running JDK 21+ FFM server accumulates one `quiche_recv_info` and one `quiche_send_info` per
 * connection (plus one extra `recv_info` per migration, per #395) for as long as the process runs, no
 * matter how diligently the driver calls `recvInfoFree`/`sendInfoFree`.
 */
class FfmRecvSendInfoArenaLifecycleTest {
    @Test
    fun everyConnectionsRecvAndSendInfoArenaIsActuallyReleasable() {
        val expectedBackend = System.getProperty(EXPECTED_BACKEND_PROPERTY)
        if (expectedBackend != "ffm") {
            // Not a failure: CI's quiche-jvm job runs this exact suite on the JNI and JDK17-JNI legs
            // too, where JNI frees recv_info/send_info correctly and #397 does not apply. Recorded
            // (not silently skipped) so the CI skip inventory still sees it on every run.
            recordSkip(
                FfmRecvSendInfoArenaLifecycleTest::class,
                SkipReason.OptInLaneNotRequested(
                    "#397 is FFM-only; this run's $EXPECTED_BACKEND_PROPERTY was " +
                        "'$expectedBackend', not 'ffm'. Run with -PquicheJvmBackend=ffm to " +
                        "exercise this regression.",
                ),
            )
            assumeTrue("FFM backend not requested for this run — see the recorded skip above", false)
            error("unreachable") // assumeTrue(false) aborts via AssumptionViolatedException
        }

        val api = loadQuicheApi()
        val concrete = concreteApi(api)
        val actualBackend = backendId(concrete)
        assertEquals(
            "ffm",
            actualBackend,
            "The build asked for the FFM backend ($EXPECTED_BACKEND_PROPERTY=ffm) but the runtime " +
                "loaded '$actualBackend'. Either the java21 classpath shadowing (build.gradle.kts) " +
                "has stopped taking effect (#399), or this JVM is older than 21 — either way, #397 " +
                "was not exercised.",
        )

        // Drive CONNECTION_COUNT connections' worth of recv_info/send_info through the real API,
        // the same recvInfoNew -> recvInfoFree / sendInfoNew -> sendInfoFree round trip
        // CommonJvmWithQuicConnection makes per connection.
        val counts = RecvSendInfoCallCounts(concrete)
        val bufferFactory = BufferFactory.network()
        repeat(CONNECTION_COUNT) { i ->
            val from = InetSocketAddress("127.0.0.1", 40000 + i).toNativeSockAddr(bufferFactory)
            val to = InetSocketAddress("127.0.0.1", 50000 + i).toNativeSockAddr(bufferFactory)
            try {
                val recvInfo = counts.recvInfoNew(from.address, from.length, to.address, to.length)
                val sendInfo = counts.sendInfoNew()
                counts.recvInfoFree(recvInfo)
                counts.sendInfoFree(sendInfo)
            } finally {
                from.free()
                to.free()
            }
        }

        // The trap, made explicit: every free this test made was in fact CALLED. A call-counting
        // delegate alone would report this and stop — see the class KDoc for why that is not proof
        // #397 is fixed.
        assertEquals(CONNECTION_COUNT, counts.recvInfoNewCalls, "recvInfoNew call count")
        assertEquals(CONNECTION_COUNT, counts.recvInfoFreeCalls, "recvInfoFree call count")
        assertEquals(CONNECTION_COUNT, counts.sendInfoNewCalls, "sendInfoNew call count")
        assertEquals(CONNECTION_COUNT, counts.sendInfoFreeCalls, "sendInfoFree call count")

        // The real assertion: what KIND of arena backs those allocations? A per-connection
        // Arena.ofConfined()/.ofShared() is a different session kind from Arena.ofAuto() — see the
        // class KDoc for why .scope().javaClass is the non-destructive way to tell them apart, and
        // why this never calls .close() on anything reached through loadQuicheApi().
        assertArenaIsNotProcessWideAutoKind(concrete, "recvInfoArena")
        assertArenaIsNotProcessWideAutoKind(concrete, "sendInfoArena")
    }

    /**
     * Reflectively fetches the `private val` [fieldName] arena from [concrete] (an `FfmQuicheApi`
     * instance, matched by simple name — see [backendId] for why type access isn't available here)
     * and asserts its session *kind* is not the process-wide `Arena.ofAuto()` kind.
     *
     * Non-destructive by construction: the only calls made against [concrete]'s field are the field
     * read and `.scope()`, a pure accessor. The `Arena.ofAuto()`/`.ofConfined()`/`.ofShared()`
     * references created here to name the alternatives are local, owned by this call, and closed by
     * it (`.ofAuto()`'s cannot be closed at all, so it is simply left for the GC — see the class
     * KDoc's "Why not `.close()`" section for why nothing reached through [loadQuicheApi] is ever
     * closed).
     */
    private fun assertArenaIsNotProcessWideAutoKind(
        concrete: QuicheApi,
        fieldName: String,
    ) {
        val field = concrete.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val productionArena = field.get(concrete) as Arena
        val productionSessionClass = productionArena.scope().javaClass

        val autoSessionClass = Arena.ofAuto().scope().javaClass // never closed: ofAuto() can't be

        val confinedReference = Arena.ofConfined()
        val confinedSessionClass =
            try {
                confinedReference.scope().javaClass
            } finally {
                confinedReference.close()
            }

        val sharedReference = Arena.ofShared()
        val sharedSessionClass =
            try {
                sharedReference.scope().javaClass
            } finally {
                sharedReference.close()
            }

        assertNotEquals(
            autoSessionClass,
            productionSessionClass,
            "FfmQuicheApi.$fieldName's scope() is a $productionSessionClass — the SAME session " +
                "class a freshly created Arena.ofAuto() reports. That IS #397: $fieldName is " +
                "Arena.ofAuto(), held as an instance field on the process-wide FfmQuicheApi " +
                "singleton (#202's dlclose-safety requirement). Arena.ofAuto() only releases memory " +
                "once the Arena object itself becomes GC-unreachable, and this Arena is reachable " +
                "for the entire process lifetime via the loadQuicheApi() singleton — so " +
                "recvInfoFree/sendInfoFree calling into it can never be more than a no-op, no " +
                "matter how many connections drove $CONNECTION_COUNT balanced new/free pairs " +
                "through it above. A real long-running JDK 21+ server leaks one recv_info and one " +
                "send_info per connection (plus one extra recv_info per migration, per #395) for as " +
                "long as the process runs. A per-connection Arena.ofConfined() would report " +
                "$confinedSessionClass here instead; Arena.ofShared() would report " +
                "$sharedSessionClass. Fixing #397 means $fieldName's allocations coming from one of " +
                "those per-connection, closed-on-teardown kinds, not from this shared singleton " +
                "field.",
        )
    }

    /** Sees through [RecvInfoGuardQuicheApi] (`-Dquic.recvInfoGuard=1`) to the concrete backend. */
    private fun concreteApi(api: QuicheApi): QuicheApi = (api as? RecvInfoGuardQuicheApi)?.delegate ?: api

    /**
     * Names the concrete backend by simple name, the same idiom [JvmQuicheBackendIdentityTest] (#399)
     * uses: `FfmQuicheApi` lives in `jvm21Main`, which is not on this source set's compile classpath,
     * so it cannot be matched by type.
     */
    private fun backendId(concrete: QuicheApi): String =
        when {
            concrete is JniQuicheApi -> "jni"
            concrete::class.simpleName == "FfmQuicheApi" -> "ffm"
            else -> "unknown(${concrete::class.simpleName})"
        }

    private companion object {
        const val EXPECTED_BACKEND_PROPERTY = "quiche.expectedJvmBackend"
        const val CONNECTION_COUNT = 25
    }
}

/**
 * Records recv_info/send_info new/free calls made through [delegate] — the call-counting instrument
 * [FfmRecvSendInfoArenaLifecycleTest]'s KDoc names as the trap: these counts balance even against
 * #397's no-op frees, which is exactly why the test does not stop here.
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
    ): QuicheRecvInfo = delegate.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen).also { recvInfoNewCalls++ }

    override fun recvInfoFree(info: QuicheRecvInfo) {
        delegate.recvInfoFree(info)
        recvInfoFreeCalls++
    }

    override fun sendInfoNew(): QuicheSendInfo = delegate.sendInfoNew().also { sendInfoNewCalls++ }

    override fun sendInfoFree(info: QuicheSendInfo) {
        delegate.sendInfoFree(info)
        sendInfoFreeCalls++
    }
}
