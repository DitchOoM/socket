package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicPassiveMigrationTestSuite] — the Android counterpart of
 * [QuicPassiveMigrationTests] (JVM) / [LinuxQuicPassiveMigrationTests]. Replaces the former hand-copy,
 * which inlined its own rebinding proxy; both JVM and Android now drive the shared
 * [DatagramChannelRebindingProxy] (in `sharedJvmTestProtocol`). Inherits the passive NAT-rebind survival
 * test: a stream keeps round-tripping after the proxy swaps its upstream source port under the connection.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicPassiveMigrationTests : QuicPassiveMigrationTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override fun createRebindingProxy(serverPort: Int): RebindingProxy = DatagramChannelRebindingProxy(serverPort)

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
