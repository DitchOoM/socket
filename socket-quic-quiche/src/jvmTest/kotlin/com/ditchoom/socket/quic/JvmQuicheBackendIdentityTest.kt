package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Proves which JVM quiche backend a test run actually used (#399).
 *
 * CI has a dedicated `QUIC quiche JVM (FFM)` job whose entire purpose is to exercise the second JVM
 * backend. Selection is pure classpath ordering — `build.gradle.kts` prepends the `java21`
 * compilation output so its `QuicheApiLoaderKt` shadows the base one — which leaves **no trace** in
 * the build log, no assertion anywhere, and nothing in the test XML. Before this test, if the
 * property ever stopped taking effect (a rename, an `afterEvaluate` ordering change, a compilation
 * renamed away from `java21`) that job would keep passing while silently running JNI a second time,
 * and the FFM backend would go untested behind a green badge.
 *
 * That is the same failure shape as #390 (the JNI backend running zero shared suites while
 * reporting green) and the loud-skips work: **a lane reporting PASS having exercised nothing.**
 *
 * It is also load-bearing for anything JDK-21-specific — #397 (FFM `recvInfoFree` is a no-op over a
 * process-wide `Arena.ofAuto()`) is an FFM-only leak that only a genuine FFM run can ever catch.
 *
 * The loader already documents that there is intentionally no silent fallback to JNI. That
 * guarantee covers a *broken* FFM; it does not cover an FFM that was never selected. This closes
 * that half by making the build state its intent and the runtime prove it — a mismatch in **either**
 * direction fails loudly.
 */
class JvmQuicheBackendIdentityTest {
    @Test
    fun theLoadedBackendIsTheOneTheBuildAskedFor() {
        val expected = System.getProperty(EXPECTED_PROPERTY)
        assertNotNull(
            expected,
            "$EXPECTED_PROPERTY was not set. The build must declare which backend it selected — an " +
                "undeclared intent is exactly the unobservable state this test exists to remove.",
        )

        val actual = backendIdOf(loadQuicheApi())
        assertEquals(
            expected,
            actual,
            "quiche JVM backend mismatch: the build selected '$expected' but the runtime loaded " +
                "'$actual'. If this is the FFM lane, the java21 classpath shadowing has stopped " +
                "taking effect and this job is running JNI a second time (#399).",
        )
    }

    /**
     * Names the concrete backend, seeing through [RecvInfoGuardQuicheApi] when the recv_info guard
     * is enabled. `FfmQuicheApi` lives in `jvm21Main` and is not on this source set's compile
     * classpath, so it is matched by simple name rather than by type.
     */
    private fun backendIdOf(api: QuicheApi): String {
        val concrete = (api as? RecvInfoGuardQuicheApi)?.delegate ?: api
        return when {
            concrete is JniQuicheApi -> "jni"
            concrete::class.simpleName == "FfmQuicheApi" -> "ffm"
            else -> "unknown(${concrete::class.simpleName})"
        }
    }

    private companion object {
        const val EXPECTED_PROPERTY = "quiche.expectedJvmBackend"
    }
}
