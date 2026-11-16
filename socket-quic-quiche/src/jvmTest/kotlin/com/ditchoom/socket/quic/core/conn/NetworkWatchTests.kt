package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class NetworkWatchTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val watch = NetworkWatch()

    @Test
    fun aUsableOrChangedNetworkNudges() {
        assertEquals(NetworkStep.Nudge, watch.changed(NetworkChange.Usable))
        assertEquals(NetworkStep.Nudge, watch.changed(NetworkChange.Rehomed))
    }

    @Test
    fun noUsableNetworkWaitsAndAnIdleTimeoutDuringItIsNetworkLost() {
        assertEquals(NetworkStep.Wait, watch.changed(NetworkChange.Gone))
        assertSame(ConnEnd.NetworkLost, watch.endOf(ConnEnd.IdleTimeout))
        assertIs<ConnEnd.ByPeer>(watch.endOf(ConnEnd.ByPeer(QuicError.NoError)), "a peer's close says itself")
    }

    @Test
    fun aUsableNetworkBackLeavesAnIdleTimeoutAsItIs() {
        watch.changed(NetworkChange.Gone)
        watch.changed(NetworkChange.Usable)
        assertSame(ConnEnd.IdleTimeout, watch.endOf(ConnEnd.IdleTimeout))
    }

    @Test
    fun aNetworkWithoutTheBoundAddressEnds() {
        assertEquals(NetworkStep.End, watch.changed(NetworkChange.Moved))
    }
}
