package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Android (quiche JNI) member of the shared [QuicConcurrencySoakTestSuite] — the Android counterpart of
 * [QuicConcurrencySoakTests] (JVM) / [LinuxQuicConcurrencySoakTests]. Replaces the former hand-copy
 * (which also duplicated `TrackingBufferFactory` locally — now inherited from socket-testsuite's
 * commonMain). Inherits all five soak tests: 20- and 64-way concurrent streams on one connection, 8- and
 * 24-way concurrent connections, and the 128-round per-operation buffer-leak guard.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicConcurrencySoakTests : QuicConcurrencySoakTestSuite() {
    override fun testTlsConfig() = AndroidTestCerts.tlsConfig

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
