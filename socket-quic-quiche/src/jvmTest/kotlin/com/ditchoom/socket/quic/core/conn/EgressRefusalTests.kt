package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.nativeBytes
import com.ditchoom.socket.quic.core.testing.stubClientPaths
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.BatchLimits
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.trace.TraceCapture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * quiche refusing to write a datagram is not "nothing to send": the connection ends, processing failed with the code
 * quiche refused with, instead of falling silent until its idle timeout.
 */
class EgressRefusalTests {
    @Test
    fun aRefusedWriteIsReportedWithQuichesCodeAndKeepsWhatWasWrittenBeforeIt() {
        val api = StubQuicheApi()
        val q = QuicheConnRef(api, QuicheConn(1L))
        val flight = ConnFlight(FlightSize(16), FlightArena(nativeBytes(8192)), PathKey(4, 1, 0L, 1L), TraceCapture.Off, 0L)
        val out = SendBatch(nativeBytes(4 * 1500), BatchLimits(4, 1500))
        api.connSendQueue.addAll(listOf(100, BUFFER_TOO_SHORT))
        val egress = Egress(q, ConnCounts(), { Nanos(0) })

        assertEquals(Wrote.Refused, egress.write(out, stubClientPaths(q), flight))

        assertEquals(1, out.pending, "the datagram before the refusal is in the batch")
        assertEquals(BUFFER_TOO_SHORT, egress.refusal().code)
    }

    @Test
    fun aConnectionWhoseQuicheRefusesToWriteEndsWithTheRefusal() {
        val (end, api) = endAfterRefusal(BUFFER_TOO_SHORT)
        val failed = assertIs<ConnEnd.ProcessingFailed>(end, "a refusal ends the connection at once")
        assertEquals(BUFFER_TOO_SHORT, assertIs<QuicheSendRefused>(failed.thrown.throwable).code)
        assertEquals(listOf<QuicError>(QuicError.ProcessingFailed), api.closeErrors, "quiche is asked to close it on the wire")
    }

    /** A TLS alert raised in the handshake quiche ran while writing is quiche's own error: the end is that, not the code. */
    @Test
    fun aRefusalAfterQuicheRecordedItsOwnErrorEndsWithThatError() {
        val (end, _) = endAfterRefusal(TLS_FAIL) { it.localError = QuicError.CryptoError(BAD_CERTIFICATE) }
        assertEquals(QuicError.CryptoError(BAD_CERTIFICATE), assertIs<ConnEnd.ByLocal>(end).error)
    }

    /** quiche refuses with InvalidState when it has no path to write on. */
    @Test
    fun aRefusalForWantOfAPathEndsWithNoUsablePath() {
        val (end, _) = endAfterRefusal(INVALID_STATE)
        assertIs<ConnEnd.NoUsablePath>(end)
    }

    /** A connection that sent its first flight, whose quiche then refuses the next write with [code]: how it ended. */
    private fun endAfterRefusal(
        code: Int,
        setup: (StubQuicheApi) -> Unit = {},
    ): Pair<ConnEnd, StubQuicheApi> {
        val api = StubQuicheApi()
        val io = FakeIo()
        var sent = 0
        io.onSend = { pending ->
            sent += pending
            SendBatchResult.AllTaken
        }
        val rig = ConnRig(api, io = io)
        api.connSendQueue.addLast(1200)
        rig.pass(0)
        assertEquals(1, sent)
        assertEquals(emptyList(), rig.sink.ended)

        setup(api)
        api.connSendQueue.addLast(code)
        rig.conn.submit(Touch)
        rig.pass(1_000_000L)
        return rig.sink.ended.single() to api
    }

    private object Touch : ConnWork() {
        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) = Unit
    }

    private companion object {
        /** quiche's `QUICHE_ERR_BUFFER_TOO_SHORT`: a refusal with nothing of quiche's own behind it. */
        const val BUFFER_TOO_SHORT = -2

        /** quiche's `QUICHE_ERR_INVALID_STATE`. */
        const val INVALID_STATE = -6

        /** quiche's `QUICHE_ERR_TLS_FAIL`. */
        const val TLS_FAIL = -10

        /** TLS alert 42. */
        const val BAD_CERTIFICATE = 42
    }
}
