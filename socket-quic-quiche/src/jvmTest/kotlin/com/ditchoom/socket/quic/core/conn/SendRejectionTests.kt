package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.QUICHE_ERR_DONE
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * What the core does with each return of `quiche_conn_send` that is not a datagram. A refusal ends the connection at
 * once with a typed end; only "nothing more" is quiet. Every rig's clock stands at zero or jumps past the idle timeout
 * after the end, so a close observed here came from the send result and never from the idle timer.
 */
class SendRejectionTests {
    @Test
    fun everyReturnOfQuicheSendIsOneOfWroteDoneOrRefused() {
        assertEquals(Step.Wrote(1200), stepOf(1200))
        assertEquals(Step.Done, stepOf(0))
        assertEquals(Step.Done, stepOf(QUICHE_ERR_DONE))
        assertEquals(Step.Refused(BUFFER_TOO_SHORT), stepOf(BUFFER_TOO_SHORT))
        assertEquals(Step.Refused(INVALID_STATE), stepOf(INVALID_STATE))
        assertEquals(Step.Refused(TLS_FAIL), stepOf(TLS_FAIL))
    }

    /** quiche answering every send with INVALID_STATE, as two patched quiche bugs once did, is an end at once, not a silent idle timeout. */
    @Test
    fun aRefusalOnEverySendEndsTheConnectionAtOnceNotAtItsIdleTimeout() {
        val api = StubQuicheApi()
        api.connTimeout = IDLE
        api.connSendFallback = INVALID_STATE
        val rig = ConnRig(api)

        rig.pass(0)

        assertIs<ConnEnd.NoUsablePath>(rig.sink.ended.single(), "the refusal ends the connection in its first pass")
        assertEquals(0L, rig.clock.at.value, "the clock never moved: the end came from the send result")
        assertEquals(listOf<QuicError>(QuicError.ProcessingFailed), api.closeErrors, "quiche is asked to close it on the wire")
        rig.pass(IDLE.inWholeNanoseconds + 1)
        assertEquals(1, rig.sink.ended.size, "the end is reported once, the idle timeout never ends it again")
    }

    /** A refusal with nothing of quiche's own behind it ends processing failed with its code, even when it never lets up. */
    @Test
    fun aBufferTooShortOnEverySendEndsProcessingFailedWithItsCode() {
        val api = StubQuicheApi()
        api.connSendFallback = BUFFER_TOO_SHORT
        val rig = ConnRig(api)

        rig.pass(0)

        val failed = assertIs<ConnEnd.ProcessingFailed>(rig.sink.ended.single())
        assertEquals(BUFFER_TOO_SHORT, assertIs<QuicheSendRefused>(failed.thrown.throwable).code)
    }

    /** One refusal, then the CONNECTION_CLOSE quiche writes once it accepts the close: that datagram still leaves. */
    @Test
    fun aRefusalOnceStillLetsTheCloseFrameLeave() {
        val api = StubQuicheApi()
        api.closesOnLocalClose = true
        api.connSendQueue += INVALID_STATE
        api.connSendQueue += 1200
        api.connSendFallback = QUICHE_ERR_DONE
        val io = FakeIo()
        var sent = 0
        io.onSend = { pending ->
            sent += pending
            SendBatchResult.AllTaken
        }
        val rig = ConnRig(api, io = io)

        rig.pass(0)

        assertIs<ConnEnd.NoUsablePath>(rig.sink.ended.single())
        assertEquals(1, sent, "the follow-up write puts the CONNECTION_CLOSE on the wire")
    }

    /** "Nothing more to send" is the normal end of a drain: it ends nothing, however many turns ask. */
    @Test
    fun nothingMoreToSendEndsNothing() {
        val api = StubQuicheApi()
        api.connSendFallback = QUICHE_ERR_DONE
        val rig = ConnRig(api)

        for (turn in 0L..TURNS) rig.pass(turn * 1_000_000L)

        assertEquals(emptyList(), rig.sink.ended)
        assertEquals(emptyList(), api.closeErrors)
    }

    /** A refusal at the very first write: the connection ends before any datagram of its own was sent. */
    @Test
    fun aRefusalAtTheFirstWriteSendsNothingAndEnds() {
        val api = StubQuicheApi()
        api.connSendFallback = INVALID_STATE
        val io = FakeIo()
        var sent = 0
        io.onSend = { pending ->
            sent += pending
            SendBatchResult.AllTaken
        }
        val rig = ConnRig(api, io = io)

        rig.pass(0)

        assertEquals(0, sent, "quiche produced nothing, so nothing was sent")
        assertIs<ConnEnd.NoUsablePath>(rig.sink.ended.single())
    }

    /** The step [raw] folds to, as a comparable value. */
    private fun stepOf(raw: Int): Step =
        DatagramStep(raw).fold(done = { Step.Done }, wrote = { Step.Wrote(it) }, refused = { Step.Refused(it) })

    private sealed interface Step {
        data object Done : Step

        data class Wrote(
            val length: Int,
        ) : Step

        data class Refused(
            val code: Int,
        ) : Step
    }

    private companion object {
        /** quiche's `QUICHE_ERR_BUFFER_TOO_SHORT`. */
        const val BUFFER_TOO_SHORT = -2

        /** quiche's `QUICHE_ERR_INVALID_STATE`. */
        const val INVALID_STATE = -6

        /** quiche's `QUICHE_ERR_TLS_FAIL`. */
        const val TLS_FAIL = -10
        val IDLE = 30.seconds
        const val TURNS = 20L
    }
}
