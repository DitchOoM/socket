package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android (quiche **JNI**) member of [FailedProbeConnectionIdTestSuite].
 *
 * The member closest to the incident: #447 was measured on a phone, on a handoff whose probe went
 * unanswered, and Android links its own `libquiche.so` built for a different triple than any desktop
 * lane. A green JVM guard says the driver is right; only this one says the binary that ships to the
 * device behaves the same way.
 *
 * Lives in `androidInstrumentedTest` rather than being inherited because that source set deliberately
 * does not `dependsOn(commonTest)`; the suite reaches it through the `src/sharedQuicheTestSuites/kotlin`
 * srcDir both source sets compile.
 */
@RunWith(AndroidJUnit4::class)
class AndroidFailedProbeConnectionIdTests : FailedProbeConnectionIdTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options)

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidFailedProbeConnectionIdTests::class, block)
}
