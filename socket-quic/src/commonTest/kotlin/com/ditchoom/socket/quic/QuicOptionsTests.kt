package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class QuicOptionsTests {
    @Test
    fun validOptions_constructsSuccessfully() {
        val options = QuicOptions(alpnProtocols = listOf("h3"))
        kotlin.test.assertEquals(listOf("h3"), options.alpnProtocols)
        kotlin.test.assertEquals(10_485_760, options.initialMaxData)
        kotlin.test.assertEquals(1350, options.maxUdpPayloadSize)
        kotlin.test.assertTrue(options.verifyPeer)
    }

    @Test
    fun multipleAlpnProtocols() {
        val options = QuicOptions(alpnProtocols = listOf("h3", "h3-29"))
        kotlin.test.assertEquals(2, options.alpnProtocols.size)
    }

    @Test
    fun emptyAlpnProtocols_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = emptyList())
        }
    }

    @Test
    fun negativeInitialMaxData_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxData = -1)
        }
    }

    @Test
    fun negativeInitialMaxStreamDataBidiLocal_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxStreamDataBidiLocal = -1)
        }
    }

    @Test
    fun negativeInitialMaxStreamDataBidiRemote_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxStreamDataBidiRemote = -1)
        }
    }

    @Test
    fun negativeInitialMaxStreamDataUni_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxStreamDataUni = -1)
        }
    }

    @Test
    fun negativeInitialMaxStreamsBidi_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxStreamsBidi = -1)
        }
    }

    @Test
    fun negativeInitialMaxStreamsUni_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), initialMaxStreamsUni = -1)
        }
    }

    @Test
    fun negativeIdleTimeout_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), idleTimeout = (-1).seconds)
        }
    }

    @Test
    fun zeroIdleTimeout_succeeds() {
        // Zero means no timeout (RFC 9000 §10.1)
        val options = QuicOptions(alpnProtocols = listOf("h3"), idleTimeout = 0.seconds)
        kotlin.test.assertEquals(0.seconds, options.idleTimeout)
    }

    @Test
    fun maxUdpPayloadSizeBelowMinimum_throws() {
        assertFailsWith<IllegalArgumentException> {
            QuicOptions(alpnProtocols = listOf("h3"), maxUdpPayloadSize = 1199)
        }
    }

    @Test
    fun maxUdpPayloadSizeAtMinimum_succeeds() {
        val options = QuicOptions(alpnProtocols = listOf("h3"), maxUdpPayloadSize = 1200)
        kotlin.test.assertEquals(1200, options.maxUdpPayloadSize)
    }

    @Test
    fun zeroStreamLimits_succeeds() {
        // Zero means no streams of that type allowed
        val options =
            QuicOptions(
                alpnProtocols = listOf("h3"),
                initialMaxStreamsBidi = 0,
                initialMaxStreamsUni = 0,
            )
        kotlin.test.assertEquals(0, options.initialMaxStreamsBidi)
        kotlin.test.assertEquals(0, options.initialMaxStreamsUni)
    }

    @Test
    fun dataCopy_preservesAllFields() {
        val original =
            QuicOptions(
                alpnProtocols = listOf("h3"),
                initialMaxData = 5_000_000,
                maxUdpPayloadSize = 1400,
                disableActiveMigration = true,
                verifyPeer = false,
            )
        val copy = original.copy(idleTimeout = 60.seconds)
        kotlin.test.assertEquals(5_000_000, copy.initialMaxData)
        kotlin.test.assertEquals(1400, copy.maxUdpPayloadSize)
        kotlin.test.assertTrue(copy.disableActiveMigration)
        kotlin.test.assertFalse(copy.verifyPeer)
        kotlin.test.assertEquals(60.seconds, copy.idleTimeout)
    }
}
