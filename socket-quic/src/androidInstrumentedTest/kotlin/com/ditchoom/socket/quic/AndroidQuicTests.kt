package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android instrumented tests — runs on real device/emulator.
 * Validates that the JNI native lib loads and the QUIC engine creates.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicTests {
    @Test
    fun nativeLibLoads() {
        System.loadLibrary("quiche_jni")
    }

    @Test
    fun quicEngineCreates() {
        val engine = defaultQuicEngine()
        assertNotNull(engine)
        engine.close()
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
