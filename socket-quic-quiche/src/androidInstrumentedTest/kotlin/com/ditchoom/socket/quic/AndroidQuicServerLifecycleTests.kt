package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) run of the shared [QuicServerLifecycleTestSuite] — the Android counterpart of
 * [JvmQuicServerLifecycleTestSuite] / [LinuxQuicServerLifecycleTests] / [AppleQuicServerLifecycleTests].
 *
 * Inherits every black-box lifecycle invariant (close returns promptly, blocking handlers terminate,
 * rapid bind/close cycles don't hang, the incoming-streams flow completes on connection close). The
 * JVM-only reflection assertion on `serverJob` state lives in [JvmQuicServerLifecycleTestSuite] and is
 * deliberately not mirrored here — like Linux/Apple, Android relies on the externally observable
 * consequences of the same invariant.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicServerLifecycleTests : QuicServerLifecycleTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
