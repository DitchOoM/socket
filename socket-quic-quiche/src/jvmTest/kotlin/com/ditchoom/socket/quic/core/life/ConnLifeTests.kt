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
    fun aCloseWeIssuedEndsByLocalEvenWhenThePeersCloseArrivesBeforeQuicheReportsClosed() {
        life.close(QuicError.NoError)
        // The peer's CONNECTION_CLOSE crossed ours: quiche, closing, read it and recorded both errors.
        api.localError = QuicError.NoError
        api.peerError = QuicError.NoError
        api.closed = true
        assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(life.quicheEnd()).error)
    }

    @Test
    fun aCloseQuicheDeclinedBecauseThePeerClosedFirstEndsByPeer() {
        api.peerError = QuicError.ProtocolViolation
        api.connCloseResult = QUICHE_ERR_DONE
        life.close(QuicError.NoError)
        assertEquals(QuicError.ProtocolViolation, assertIs<ConnEnd.ByPeer>(life.quicheEnd()).error)
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

    private companion object {
        const val QUICHE_ERR_DONE = -1
    }
}
