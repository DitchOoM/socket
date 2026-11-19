package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The receive-window ceilings handed to quiche: enough that one stream can fill a fast long path. */
class FlowWindowDefaultsTests {
    @Test
    fun theDefaultStreamCeilingSustainsEightyPercentOfTwoPointFiveGigabitAtFiftyMilliseconds() {
        val handed = windowsHandedToQuiche(QuicOptions(alpnProtocols = listOf("test")))
        val perRoundTrip = handed.stream / 2
        val needed = (0.8 * LINK_BYTES_PER_SECOND * 0.051).toLong()
        assertTrue(perRoundTrip >= needed, "stream window ${handed.stream} carries $perRoundTrip per RTT, needs $needed")
        assertTrue(handed.connection >= handed.stream, "connection ${handed.connection} < stream ${handed.stream}")
    }

    @Test
    fun aCallerChosenCeilingIsHandedThroughUnchanged() {
        val fc = FlowControl(maxStreamWindow = 8_000_000, maxConnectionWindow = 9_000_000)
        val handed = windowsHandedToQuiche(QuicOptions(alpnProtocols = listOf("test"), flowControl = fc))
        assertEquals(8_000_000L, handed.stream)
        assertEquals(9_000_000L, handed.connection)
    }

    private class Windows(
        val stream: Long,
        val connection: Long,
    )

    private fun windowsHandedToQuiche(options: QuicOptions): Windows {
        var stream = 0L
        var connection = 0L
        val calls =
            object : QuicConfigCalls by SimQuicConfigCalls(StubQuicheApi(), QuicheConfig(1L)) {
                override fun setMaxStreamWindow(v: Long) {
                    stream = v
                }

                override fun setMaxConnectionWindow(v: Long) {
                    connection = v
                }
            }
        applyQuicOptions(options, calls, QuicRole.Client)
        return Windows(stream, connection)
    }

    private companion object {
        const val LINK_BYTES_PER_SECOND = 2.5e9 / 8
    }
}
