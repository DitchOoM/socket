package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class QuicOptionsTests {
    @Test
    fun validOptions_constructsSuccessfully() {
        val options = QuicOptions(alpnProtocols = listOf("h3"))
        kotlin.test.assertEquals(listOf("h3"), options.alpnProtocols)
        kotlin.test.assertEquals(10_485_760, options.flowControl.initialMaxData)
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
    fun negativeFlowControlData_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxData = -1)
        }
    }

    @Test
    fun negativeFlowControlBidiLocal_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxStreamDataBidiLocal = -1)
        }
    }

    @Test
    fun negativeFlowControlBidiRemote_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxStreamDataBidiRemote = -1)
        }
    }

    @Test
    fun negativeFlowControlUni_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxStreamDataUni = -1)
        }
    }

    @Test
    fun negativeFlowControlStreamsBidi_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxStreamsBidi = -1)
        }
    }

    @Test
    fun negativeFlowControlStreamsUni_throws() {
        assertFailsWith<IllegalArgumentException> {
            FlowControl(initialMaxStreamsUni = -1)
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
        val fc = FlowControl(initialMaxStreamsBidi = 0, initialMaxStreamsUni = 0)
        kotlin.test.assertEquals(0, fc.initialMaxStreamsBidi)
        kotlin.test.assertEquals(0, fc.initialMaxStreamsUni)
    }

    @Test
    fun dataCopy_preservesAllFields() {
        val original =
            QuicOptions(
                alpnProtocols = listOf("h3"),
                flowControl = FlowControl(initialMaxData = 5_000_000),
                maxUdpPayloadSize = 1400,
                disableActiveMigration = true,
                verifyPeer = false,
            )
        val copy = original.copy(idleTimeout = 60.seconds)
        kotlin.test.assertEquals(5_000_000, copy.flowControl.initialMaxData)
        kotlin.test.assertEquals(1400, copy.maxUdpPayloadSize)
        kotlin.test.assertTrue(copy.disableActiveMigration)
        kotlin.test.assertFalse(copy.verifyPeer)
        kotlin.test.assertEquals(60.seconds, copy.idleTimeout)
    }

    @Test
    fun congestionControl_exhaustiveWhen() {
        val algorithms: List<CongestionControl> =
            listOf(
                CongestionControl.Reno,
                CongestionControl.Cubic(),
                CongestionControl.Bbr2,
            )
        for (cc in algorithms) {
            // Compiler enforces exhaustiveness — every branch must be handled
            val name =
                when (cc) {
                    is CongestionControl.Reno -> "reno"
                    is CongestionControl.Cubic -> "cubic(hystart=${cc.enableHystart})"
                    is CongestionControl.Bbr2 -> "bbr2"
                }
            kotlin.test.assertTrue(name.isNotEmpty())
        }
    }

    @Test
    fun pacing_exhaustiveWhen() {
        val configs: List<Pacing> =
            listOf(
                Pacing.Disabled,
                Pacing.Unlimited,
                Pacing.Limited(1_000_000),
            )
        for (p in configs) {
            val desc =
                when (p) {
                    is Pacing.Disabled -> "off"
                    is Pacing.Unlimited -> "on"
                    is Pacing.Limited -> "limited(${p.maxBytesPerSec})"
                }
            kotlin.test.assertTrue(desc.isNotEmpty())
        }
    }

    @Test
    fun pacingLimited_zeroBytesPerSec_throws() {
        assertFailsWith<IllegalArgumentException> {
            Pacing.Limited(0)
        }
    }

    @Test
    fun pacingLimited_negativeBytesPerSec_throws() {
        assertFailsWith<IllegalArgumentException> {
            Pacing.Limited(-1)
        }
    }

    @Test
    fun cubicHystart_defaultTrue() {
        val cubic = CongestionControl.Cubic()
        kotlin.test.assertTrue(cubic.enableHystart)
    }

    @Test
    fun cubicHystart_canDisable() {
        val cubic = CongestionControl.Cubic(enableHystart = false)
        kotlin.test.assertFalse(cubic.enableHystart)
    }

    @Test
    fun defaultCongestionControl_isCubic() {
        val options = QuicOptions(alpnProtocols = listOf("h3"))
        assertIs<CongestionControl.Cubic>(options.congestionControl)
    }

    @Test
    fun defaultPacing_isUnlimited() {
        val options = QuicOptions(alpnProtocols = listOf("h3"))
        assertIs<Pacing.Unlimited>(options.pacing)
    }
}
