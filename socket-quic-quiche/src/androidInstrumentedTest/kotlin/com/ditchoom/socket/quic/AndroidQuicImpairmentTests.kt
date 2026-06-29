package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicImpairmentTestSuite] — the Android counterpart of
 * [QuicImpairmentTests] (JVM) / [LinuxQuicImpairmentTests]. Replaces the former hand-copy, which inlined
 * its own UDP-impairment proxy; both JVM and Android now drive the shared [DatagramChannelImpairingProxy]
 * (in `sharedJvmTestProtocol`, which already carries the RejectedExecutionException teardown-race guard
 * this copy first needed — 66bec0a). Inherits all six impairment tests (loss / reorder / dup /
 * latency+jitter / burst-loss-recovery / total-blackhole anti-vacuous guard).
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicImpairmentTests : QuicImpairmentTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = DatagramChannelImpairingProxy(serverPort, policy)

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
