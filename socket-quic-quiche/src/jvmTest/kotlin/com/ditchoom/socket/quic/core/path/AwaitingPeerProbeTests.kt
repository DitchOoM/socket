package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.time.WakeState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The awaiting-peer probe, on a [PathSilence] alone: one PING once a read has waited a floor with nothing arriving and
 * nothing unanswered, the wait doubling to the cap, reset by data handed to a reader; never on an idle connection, and
 * never before the handshake. Its wake is offered exactly when it would be sent.
 */
class AwaitingPeerProbeTests {
    private val api = StubQuicheApi()
    private val silence = PathSilence(QuicheConnRef(api, QuicheConn(1L)), SilenceRule(Silence.Off, Span(CAP), PathNewsCell()))

    @Test
    fun aWaitingReadOnAQuietPathPingsOnceAtTheFloor() {
        silence.baseline()
        silence.answered(Nanos(T0))
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + FLOOR - 1), READING, 0L))
        assertEquals(T0 + FLOOR, offered(READING))
        assertEquals(ProbeStep.Pinged, silence.probe(Nanos(T0 + FLOOR), READING, 0L))
        assertEquals(1, api.ackElicitingCount)
        // The PING is owed an answer: no second probe, and no wake for one, until something arrives.
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + 10 * FLOOR), READING, 0L))
        assertEquals(NOTHING, offered(READING))
    }

    @Test
    fun eachProbeWithoutDataForTheReaderDoublesTheWaitUpToTheCap() {
        silence.baseline()
        var at = T0
        silence.answered(Nanos(at))
        val waits = ArrayList<Long>()
        repeat(6) {
            val due = offered(READING)
            waits += due - at
            assertEquals(ProbeStep.Pinged, silence.probe(Nanos(due), READING, 0L))
            // The PING's ACK arrives at once: the peer is there, but sends the reader nothing.
            at = due
            silence.answered(Nanos(at))
        }
        assertEquals(listOf(FLOOR, 2 * FLOOR, 4 * FLOOR, 8 * FLOOR, CAP, CAP), waits)
    }

    @Test
    fun dataHandedToAReaderResetsTheWait() {
        silence.baseline()
        silence.answered(Nanos(T0))
        assertEquals(ProbeStep.Pinged, silence.probe(Nanos(T0 + FLOOR), READING, 0L))
        silence.answered(Nanos(T0 + FLOOR))
        assertEquals(T0 + 3 * FLOOR, offered(READING), "the second wait is doubled")
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + FLOOR + 1), READING, 1L))
        assertEquals(T0 + 2 * FLOOR, offered(READING), "data reached the reader: the wait is the floor again")
    }

    @Test
    fun anIdleConnectionIsNeverProbedAndOffersNoWake() {
        silence.baseline()
        silence.answered(Nanos(T0))
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + 100 * FLOOR), 0, 0L))
        assertEquals(NOTHING, offered(0))
        assertEquals(0, api.ackElicitingCount)
    }

    @Test
    fun nothingIsProbedBeforeTheHandshakeCompletes() {
        silence.answered(Nanos(T0))
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + 100 * FLOOR), READING, 0L))
        assertEquals(NOTHING, offered(READING))
    }

    @Test
    fun dataHandedToQuicheAndUnansweredHoldsTheProbe() {
        silence.baseline()
        silence.answered(Nanos(T0))
        silence.handed(Nanos(T0 + 1), 1L)
        assertEquals(ProbeStep.Rested, silence.probe(Nanos(T0 + 100 * FLOOR), READING, 0L))
        silence.answered(Nanos(T0 + 2))
        assertEquals(T0 + 2 + FLOOR, offered(READING), "the answer lets the probe wait again")
    }

    /** What [PathSilence.offerWake] offers with [readers] reads waiting, or [NOTHING]. */
    private fun offered(readers: Int): Long {
        val sink = WakeSink()
        silence.offerWake(sink, readers)
        return when (sink.state()) {
            WakeState.Asked -> sink.earliest().value
            WakeState.NotAsked -> NOTHING
        }
    }

    private companion object {
        const val T0 = 5_000_000_000L
        const val FLOOR = 2_000_000_000L
        const val CAP = 30_000_000_000L
        const val READING = 1
        const val NOTHING = -1L
    }
}
