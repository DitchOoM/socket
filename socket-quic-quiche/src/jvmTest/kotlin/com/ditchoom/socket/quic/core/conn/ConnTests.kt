package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.NextDue
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.stream.Retry
import com.ditchoom.socket.quic.core.stream.StreamTable
import com.ditchoom.socket.quic.core.stream.Waiter
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A connection served by its loop over a scripted quiche, a fake socket and virtual time: no socket, no thread. */
class ConnTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val ms = 1_000_000L

    private class Counted {
        val io = FakeIo()
        var sent = 0

        init {
            io.onSend = { pending ->
                sent += pending
                SendBatchResult.AllTaken
            }
        }
    }

    @Test
    fun theFirstPassSendsTheFirstFlightAndEstablishmentIsReportedOnce() {
        val api = StubQuicheApi().apply { connSendQueue.addLast(1200) }
        val out = Counted()
        val rig = ConnRig(api, io = out.io)

        rig.pass(0)
        rig.conn.submit(Nothing())
        rig.pass(1)

        assertEquals(1, out.sent)
        assertEquals(1, rig.sink.established.size)
    }

    @Test
    fun workThatThrowsEndsTheConnectionWithItAndLaterWorkIsToldTheSameEnd() {
        val api = StubQuicheApi()
        val rig = ConnRig(api)
        val planted = IllegalStateException("planted")
        rig.pass(0)

        rig.conn.submit(Throws(planted))
        rig.pass(1)

        val end = assertIs<ConnEnd.ProcessingFailed>(rig.sink.ended.single())
        assertSame(planted, end.thrown.throwable)
        assertEquals(listOf<QuicError>(QuicError.ProcessingFailed), api.closeErrors)
        val later = Nothing()
        rig.conn.submit(later)
        assertSame(end, later.goneWith.single())
    }

    /**
     * An operation told the connection ended resumes its caller, which may ask the edge how it ended at once (a read
     * that failed reports it): the sink, which publishes the end, hears it before any parked operation does.
     */
    @Test
    fun theEndIsPublishedBeforeAParkedOperationIsToldIt() {
        val rig = ConnRig(StubQuicheApi())
        rig.pass(0)
        val endsPublishedWhenTold = ArrayList<Int>()
        val parked =
            object : Waiter {
                override fun retry(table: StreamTable): Retry = Retry.Again

                override fun connectionEnded(end: ConnEnd) {
                    endsPublishedWhenTold += rig.sink.ended.size
                }
            }
        rig.conn.submit(
            object : ConnWork() {
                override fun run(inside: Inside) = inside.streams.waitToOpen(parked)

                override fun gone(end: ConnEnd) = Unit
            },
        )
        rig.pass(1)
        rig.conn.submit(Throws(IllegalStateException("planted")))
        rig.pass(2)
        assertEquals(listOf(1), endsPublishedWhenTold, "the parked operation was told the end after the sink published it")
    }

    @Test
    fun aConnectionQuicheClosedEndsWithThePeersError() {
        val api = StubQuicheApi()
        val rig = ConnRig(api)
        rig.pass(0)
        api.peerError = QuicError.ProtocolViolation
        api.closed = true

        rig.conn.submit(Nothing())
        rig.pass(1)

        assertEquals(QuicError.ProtocolViolation, assertIs<ConnEnd.ByPeer>(rig.sink.ended.single()).error)
    }

    @Test
    fun aUsableNetworkMakesTheConnectionSendInTheSamePassInsteadOfWaitingOutItsBackedOffTimer() {
        // A PTO backed off to eight seconds: quiche sends nothing until then on its own.
        val api = StubQuicheApi().apply { connTimeout = 8.seconds }
        val out = Counted()
        val rig = ConnRig(api, io = out.io)
        rig.pass(0)
        assertEquals(0, out.sent)
        api.onProbeNow = { api.connSendQueue.addLast(60) }

        rig.conn.submit(NetworkWork(NetworkChange.Usable))
        rig.pass(10 * ms)

        assertEquals(1, api.probeNowCount, "quiche was asked for its probes now")
        assertEquals(1, out.sent, "the datagram left in the pass that heard the news, not eight seconds later")
    }

    @Test
    fun anOutageShorterThanTheIdleTimeoutDoesNotEndTheSocketAndTheConnectionSendsAtOnceWhenTheNetworkReturns() {
        val api = StubQuicheApi().apply { connTimeout = 8.seconds }
        val io = FakeIo()
        var refusing = true
        var sent = 0
        io.onSend = { pending ->
            if (refusing) {
                SendBatchResult.Partial(0, com.ditchoom.socket.quic.core.udp.UdpError.NetworkUnreachable)
            } else {
                sent += pending
                SendBatchResult.AllTaken
            }
        }
        val rig = ConnRig(api, io = io)
        rig.pass(0)
        // Every probe during the outage is refused by an unreachable network: each datagram's own fault.
        repeat(20) {
            api.connSendQueue.addLast(1200)
            rig.conn.submit(Nothing())
            rig.pass((it + 1) * 100 * ms)
        }
        assertIs<com.ditchoom.socket.quic.core.sched.SocketHealth.Live>(rig.socket.health())
        assertEquals(emptyList(), rig.sink.ended, "the connection is still up")

        refusing = false
        api.onProbeNow = { api.connSendQueue.addLast(60) }
        rig.conn.submit(NetworkWork(NetworkChange.Usable))
        rig.pass(3_000 * ms)

        assertEquals(1, sent, "the network came back: the connection sent in that pass")
    }

    @Test
    fun aTenSecondOutageKeepsTheConnectionAndTheSameAddressBackSendsInThatPass() {
        val api = StubQuicheApi().apply { connTimeout = 30.seconds }
        val out = Counted()
        val rig = ConnRig(api, io = out.io)
        rig.pass(0)

        rig.conn.submit(NetworkWork(NetworkChange.Gone))
        rig.pass(1 * ms)
        val next = rig.pass(2 * ms)
        assertEquals(NextDue.at(30_000 * ms), next, "no usable network arms nothing of its own: the idle timeout bounds it")
        for (at in 1..10) {
            rig.conn.submit(Nothing())
            rig.pass(at * 1_000 * ms)
        }
        assertEquals(emptyList(), rig.sink.ended, "a ten-second outage does not end the connection")

        api.onProbeNow = { api.connSendQueue.addLast(60) }
        rig.conn.submit(NetworkWork(NetworkChange.Usable))
        rig.pass(10_001 * ms)
        assertEquals(1, out.sent, "the datagram left in the pass that heard the network is back")
    }

    @Test
    fun anOutageLongerThanTheIdleTimeoutEndsWithNetworkLost() {
        val api = StubQuicheApi().apply { connTimeout = 5.seconds }
        val rig = ConnRig(api)
        rig.pass(0)

        rig.conn.submit(NetworkWork(NetworkChange.Gone))
        rig.pass(1 * ms)
        assertEquals(emptyList(), rig.sink.ended)
        api.timedOut = true
        api.closed = true
        rig.pass(5_000 * ms)

        assertSame(ConnEnd.NetworkLost, rig.sink.ended.single(), "the idle timeout passed with no usable network")
    }

    @Test
    fun anIdleTimeoutWithAUsableNetworkIsAnIdleTimeout() {
        val api = StubQuicheApi().apply { connTimeout = 5.seconds }
        val rig = ConnRig(api)
        rig.pass(0)
        rig.conn.submit(NetworkWork(NetworkChange.Gone))
        rig.pass(1 * ms)
        rig.conn.submit(NetworkWork(NetworkChange.Usable))
        rig.pass(2 * ms)
        api.timedOut = true
        api.closed = true
        rig.pass(5_000 * ms)

        assertSame(ConnEnd.IdleTimeout, rig.sink.ended.single())
    }

    @Test
    fun aNetworkThatReturnsWithoutTheBoundAddressEndsTheConnectionAtOnce() {
        val api = StubQuicheApi().apply { connTimeout = 30.seconds }
        val rig = ConnRig(api)
        rig.pass(0)
        rig.conn.submit(NetworkWork(NetworkChange.Gone))
        rig.pass(1 * ms)

        rig.conn.submit(NetworkWork(NetworkChange.Moved))
        rig.pass(2 * ms)

        assertSame(ConnEnd.LocalAddressGone, rig.sink.ended.single())
    }

    @Test
    fun aReleaseFreesOnceNobodyIsInsideAndEndsQueuedWork() {
        val rig = ConnRig()
        rig.pass(0)
        var freed = 0
        rig.conn.release { freed++ }
        assertEquals(1, freed)
        assertTrue(rig.sink.ended.single() is ConnEnd.Released)
        val later = Nothing()
        rig.conn.submit(later)
        assertSame(ConnEnd.Released, later.goneWith.single())
    }

    private class Nothing : ConnWork() {
        val goneWith = ArrayList<ConnEnd>()

        override fun run(inside: Inside) = Unit

        override fun gone(end: ConnEnd) {
            goneWith += end
        }
    }

    private class Throws(
        private val planted: Exception,
    ) : ConnWork() {
        override fun run(inside: Inside) = throw planted

        override fun gone(end: ConnEnd) = Unit
    }
}
