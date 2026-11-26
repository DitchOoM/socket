package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A client whose handshake completes in the serve that ends its connection, over a scripted quiche on virtual time.
 * quiche marks a handshake complete before it validates the peer's transport parameters, so one it refuses reads as
 * established and closed at once, with its own error: that handshake failed, and is never reported as complete. A
 * server that closed right after its handshake is the other case: the completion is reported, then the end.
 */
class HandshakeClosedOnCompletionConnTests {
    @Test
    fun aHandshakeOurQuicheClosesAsItCompletesIsNeverReportedComplete() {
        val failure = QuicError.TransportParameterError("initial_source_connection_id mismatch")
        val api =
            StubQuicheApi().apply {
                established = true
                closed = true
                localError = failure
            }
        val rig = ConnRig(api)
        rig.pass(0)
        assertEquals(emptyList(), rig.sink.established, "a handshake quiche closed as it completed was reported complete")
        val end = assertIs<ConnEnd.ByLocal>(rig.sink.ended.single(), "the end: ${rig.sink.ended}")
        assertEquals<QuicError>(failure, end.error)
        assertIs<HandshakeFate.NotCompleted>(rig.sink.fates.single())
    }

    @Test
    fun aHandshakeTheServerClosesAsItCompletesIsReportedCompleteThenEnded() {
        val api =
            StubQuicheApi().apply {
                established = true
                closed = true
                peerError = QuicError.ApplicationError(APP_CODE)
            }
        val rig = ConnRig(api)
        rig.pass(0)
        assertEquals(1, rig.sink.established.size, "the server closed right after its handshake: the completion is reported")
        assertIs<ConnEnd.ByPeer>(rig.sink.ended.single(), "the end: ${rig.sink.ended}")
        assertIs<HandshakeFate.Completed>(rig.sink.fates.single())
    }

    private companion object {
        const val APP_CODE = 0x2AL
    }
}
