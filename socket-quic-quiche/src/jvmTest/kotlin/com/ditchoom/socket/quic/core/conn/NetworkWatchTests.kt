package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkWatchTests {
    private val watch = NetworkWatch(NetworkRule(graceNanos = 100))

    @Test
    fun aUsableOrChangedNetworkNudges() {
        assertEquals(NetworkStep.Nudge, watch.changed(NetworkChange.Usable, Nanos(0)))
        assertEquals(NetworkStep.Nudge, watch.changed(NetworkChange.Changed, Nanos(0)))
    }

    @Test
    fun noNetworkIsLostOnlyOnceTheGracePassesAndTheGraceStartsAtTheFirstReport() {
        assertEquals(NetworkStep.Wait, watch.changed(NetworkChange.Gone, Nanos(10)))
        watch.changed(NetworkChange.Gone, Nanos(50))
        assertEquals(NetworkVerdict.Usable, watch.verdict(Nanos(109)))
        assertEquals(NetworkVerdict.Lost, watch.verdict(Nanos(110)))
        val sink = WakeSink()
        watch.offerWake(sink)
        assertEquals(Nanos(110), sink.earliest())
    }

    @Test
    fun aUsableNetworkWithinTheGraceCancelsIt() {
        watch.changed(NetworkChange.Gone, Nanos(0))
        watch.changed(NetworkChange.Usable, Nanos(50))
        assertEquals(NetworkVerdict.Usable, watch.verdict(Nanos(1_000)))
    }
}
