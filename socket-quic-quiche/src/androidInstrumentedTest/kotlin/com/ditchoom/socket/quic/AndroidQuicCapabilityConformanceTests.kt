package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Android (quiche **JNI**) member of [QuicCapabilityConformanceTestSuite].
 *
 * Android inherits its [LocalEndpointSupport.Bindable] declaration from `commonJvmMain`, which it shares
 * verbatim with the JVM — but not the runtime that has to honour it: this runs the device's own
 * `UdpSocket` actual, on Android's network stack, against the Android `libquiche.so`. A declaration made
 * in shared code and verified only on a desktop JVM is verified on one of the two platforms it speaks
 * for.
 *
 * Lives in `androidInstrumentedTest` rather than being inherited because that source set deliberately
 * does not `dependsOn(commonTest)`; the suite reaches it through the `src/sharedQuicheTestSuites/kotlin`
 * srcDir both source sets compile. Both ends run in this process over loopback, so quiche needs a real
 * cert chain + key on disk — supplied by [AndroidTestCerts].
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicCapabilityConformanceTests : QuicCapabilityConformanceTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) =
        skipOnMissingNativeLib(AndroidQuicCapabilityConformanceTests::class, block)
}
