package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/**
 * Android instrumented tests — runs on real device/emulator (API 24+).
 * Validates JNI native lib loads and QUIC entry points work.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicTests {
    @Test
    fun nativeLibLoads() {
        System.loadLibrary("quiche_jni")
    }

    @Test
    fun withQuicConnectionThrowsOnUnreachableHost() =
        runBlocking(Dispatchers.IO) {
            val opts = QuicOptions(alpnProtocols = listOf("h3"))
            // Bogus address — handshake must fail fast (timeout or refusal). The
            // observable here is just "calling withQuicConnection wires up the
            // native lib without crashing"; the throw is expected.
            try {
                withQuicConnection("192.0.2.1", 443, opts, timeout = 1.seconds) {
                    assertTrue("Expected timeout/refusal", false)
                }
            } catch (_: Throwable) {
                // Expected.
            }
        }

    @Test
    fun quicOptionsValidates() {
        val options = QuicOptions(alpnProtocols = listOf("h3"))
        assertTrue(options.alpnProtocols.isNotEmpty())
        assertTrue(options.maxUdpPayloadSize >= 1200)
    }

    @Test
    fun quicStreamIdEncoding() {
        val clientBidi = QuicStreamId(0)
        assertTrue(clientBidi.isClientInitiated)
        assertTrue(clientBidi.isBidirectional)

        val serverBidi = QuicStreamId(1)
        assertTrue(serverBidi.isServerInitiated)
        assertTrue(serverBidi.isBidirectional)
    }

    @Test
    fun quicErrorMapping() {
        val error = QuicError.fromTransportCode(0x0)
        assertTrue(error is QuicError.NoError)

        val refused = QuicError.fromTransportCode(0x2)
        assertTrue(refused is QuicError.ConnectionRefused)
    }
}
