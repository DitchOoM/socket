package com.ditchoom.socket.quic.core.life

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ConnLifeTests {
    private val api = StubQuicheApi()
    private val life = ConnLife(QuicheConnRef(api, QuicheConn(1L)))

    @Test
    fun quichesEndIsReadInOrderTimedOutPeerLocalThenNothing() {
        assertSame(ConnEnd.NoErrorRecorded, life.quicheEnd())
        api.localError = QuicError.NoError
        assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(life.quicheEnd()).error)
        api.peerError = QuicError.ProtocolViolation
        assertIs<ConnEnd.ByPeer>(life.quicheEnd())
        api.timedOut = true
        assertSame(ConnEnd.IdleTimeout, life.quicheEnd())
    }

    @Test
    fun theEndIsRecordedOnceAndPublished() {
        assertEquals(Ending.First, life.end(ConnEnd.LoopStopped))
        assertEquals(Ending.Already, life.end(ConnEnd.IdleTimeout))
        assertSame(ConnEnd.LoopStopped, assertIs<Life.Ended>(life.view()).end)
    }

    @Test
    fun observeReadsQuichesClosedFlag() {
        assertEquals(LifeStep.Open, life.observe())
        api.closed = true
        assertEquals(LifeStep.Closed, life.observe())
    }
}
