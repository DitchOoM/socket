package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals

/** The congestion controller handed to quiche when the caller chooses none, and when it chooses one. */
class CongestionControlDefaultTests {
    @Test
    fun aConnectionThatChoosesNoCongestionControlRunsBbr2() {
        assertEquals(CongestionControl.Bbr2.quicheValue, algorithmHandedToQuiche(QuicOptions(alpnProtocols = listOf("test"))))
    }

    @Test
    fun aCallerChosenCongestionControlIsHandedThrough() {
        val options = QuicOptions(alpnProtocols = listOf("test"), congestionControl = CongestionControl.Cubic())
        assertEquals(CongestionControl.Cubic().quicheValue, algorithmHandedToQuiche(options))
    }

    private fun algorithmHandedToQuiche(options: QuicOptions): Int {
        var handed = -1
        val calls =
            object : QuicConfigCalls by SimQuicConfigCalls(StubQuicheApi(), QuicheConfig(1L)) {
                override fun setCcAlgorithm(algo: Int) {
                    handed = algo
                }
            }
        applyQuicOptions(options, calls, QuicRole.Client)
        return handed
    }
}
