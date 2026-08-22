package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android (quiche **JNI**) member of [RetiredCidInFlightPacketTestSuite].
 *
 * This is the member closest to the incident. #445's field measurements are Android measurements — a
 * Samsung SM-F956U1 walking between Wi-Fi and cellular, 2 of 11 migrations killed before #441 and 3
 * of 16 after it — and Android links its own `libquiche.so`, built for a different triple than any
 * desktop lane. A green JVM guard says the patch is right; only this one says the binary that shipped
 * to the device has it.
 *
 * Lives in `androidInstrumentedTest` rather than being inherited because that source set deliberately
 * does not `dependsOn(commonTest)`; the suite reaches it through the `src/sharedQuicheTestSuites/kotlin`
 * srcDir both source sets compile. Both ends run in this process over loopback, so quiche needs a real
 * cert chain + key on disk — supplied by [AndroidTestCerts].
 */
@RunWith(AndroidJUnit4::class)
class AndroidRetiredCidInFlightPacketTests : RetiredCidInFlightPacketTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override fun platformQuicheApi(): QuicheApi = loadQuicheApi()

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        api: QuicheApi,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options, api = api)

    override suspend fun wrapTestBody(block: suspend () -> Unit) =
        skipOnMissingNativeLib(AndroidRetiredCidInFlightPacketTests::class, block)
}
