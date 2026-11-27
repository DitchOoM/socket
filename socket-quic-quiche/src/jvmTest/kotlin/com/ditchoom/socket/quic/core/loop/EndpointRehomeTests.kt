package com.ditchoom.socket.quic.core.loop

import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.edge.CloseWork
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.udp.Reopened
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.Thrown
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import java.net.BindException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A socket whose network changed under it is reopened on the loop, and its connection sends from the new socket in
 * the same pass instead of waiting out a backed-off timer. Over a scripted quiche, a fake socket and virtual time.
 */
class EndpointRehomeTests {
    private val ms = 1_000_000L

    @Test
    fun aRehomedSocketIsReopenedAndTheConnectionSendsFromItInThatPassAndRecordsIt() {
        // A PTO backed off to eight seconds: quiche sends nothing until then on its own.
        val api = StubQuicheApi().apply { connTimeout = 8.seconds }
        val io = FakeIo()
        var sent = 0
        io.onSend = { pending ->
            sent += pending
            SendBatchResult.AllTaken
        }
        val recorded = ArrayList<TraceEvent>()
        val rig = ConnRig(api, io = io, capture = TraceCapture.On(QuicTraceRecorder(TraceSink { recorded += it })))
        rig.pass(0)
        api.onProbeNow = { api.connSendQueue.addLast(60) }

        rig.rehome()
        rig.pass(10 * ms)

        assertEquals(1, io.reopens, "the socket was reopened")
        assertEquals(1, sent, "the datagram left the new socket in the pass that reopened it, not eight seconds later")
        assertEquals(emptyList(), rig.sink.ended)
        assertTrue(recorded.any { it is TraceEvent.PathState && it.phase == "Rehomed" }, "the flight recording shows it: $recorded")
    }

    @Test
    fun aSocketThatCannotBeReopenedEndsItsConnectionWithWhy() {
        val io = FakeIo()
        val refusal = BindException("Address already in use")
        io.reopenAnswer = Reopened.Refused(Thrown(refusal))
        val rig = ConnRig(StubQuicheApi(), io = io)
        rig.pass(0)

        rig.rehome()
        rig.pass(1 * ms)

        val fault = assertIs<SocketFault.ReopenRefused>(assertIs<ConnEnd.SocketFailed>(rig.sink.ended.single()).fault)
        assertEquals(refusal, fault.thrown.throwable)
    }

    /** A connection past its close point has its socket reopened by no rehome: quiche holds its close on the socket it has. */
    @Test
    fun aRehomeAfterTheCloseReopensNothing() {
        val io = FakeIo()
        io.reopenAnswer = Reopened.Refused(Thrown(BindException("Address already in use")))
        val rig = ConnRig(StubQuicheApi(), io = io)
        rig.pass(0)
        rig.conn.submit(CloseWork(QuicError.NoError))
        rig.pass(1 * ms)
        assertIs<Life.Closing>(rig.conn.life(), "precondition: the connection is closing")

        rig.rehome()
        rig.pass(2 * ms)

        assertEquals(0, io.reopens, "the socket of a closed connection was reopened")
        assertTrue(rig.sink.ended.none { it is ConnEnd.SocketFailed }, "a refused reopen replaced the close: ${rig.sink.ended}")
    }
}
