package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionEnd
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicHandshakeReached
import com.ditchoom.socket.quic.QuicRttSample
import com.ditchoom.socket.quic.QuicSendRefusal
import com.ditchoom.socket.quic.QuicSocketFault
import com.ditchoom.socket.quic.closeReason
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.HandshakeProgress
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.SocketFault
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ConnectionEndMappingTests {
    private val boom = IllegalStateException("boom")

    private val progress =
        HandshakeProgress(
            waited = 900.milliseconds,
            firstSample = RttSample.Sampled(12.milliseconds),
            prior = 333.milliseconds,
            link = LinkClass.Wifi,
            probes = 3,
            reached = HandshakeReached.PeerAnswered,
        )

    /** One of every [ConnEnd]; [variant] numbers them, and a variant missing here fails [everyEndIsCovered]. */
    private val samples: List<ConnEnd> =
        listOf(
            ConnEnd.ByPeer(QuicError.ProtocolViolation),
            ConnEnd.ByLocal(QuicError.ApplicationError(7)),
            ConnEnd.IdleTimeout,
            ConnEnd.HandshakeTimedOut(progress),
            ConnEnd.SocketFailed(SocketFault.Repeated(8, Thrown(boom))),
            ConnEnd.ProcessingFailed(Thrown(boom)),
            ConnEnd.CallerFailed(Thrown(boom)),
            ConnEnd.LoopFailed(Thrown(boom)),
            ConnEnd.LoopStopped,
            ConnEnd.ServerClosed,
            ConnEnd.ServerClosedUnsettled,
            ConnEnd.NoErrorRecorded,
            ConnEnd.Released,
            ConnEnd.NetworkLost,
            ConnEnd.LocalAddressGone,
            ConnEnd.NoUsablePath,
            ConnEnd.HandshakeSendRefused(progress, SendRefusal.PermissionDenied),
            ConnEnd.AccessDenied(QuicAccessDenial.AndroidPermissionNotHeld),
        )

    /** Exhaustive without `else`: a new [ConnEnd] does not compile until it is numbered here and sampled above. */
    private fun variant(end: ConnEnd): Int =
        when (end) {
            is ConnEnd.ByPeer -> 0
            is ConnEnd.ByLocal -> 1
            is ConnEnd.IdleTimeout -> 2
            is ConnEnd.HandshakeTimedOut -> 3
            is ConnEnd.SocketFailed -> 4
            is ConnEnd.ProcessingFailed -> 5
            is ConnEnd.CallerFailed -> 6
            is ConnEnd.LoopFailed -> 7
            is ConnEnd.LoopStopped -> 8
            is ConnEnd.ServerClosed -> 9
            is ConnEnd.ServerClosedUnsettled -> 10
            is ConnEnd.NoErrorRecorded -> 11
            is ConnEnd.Released -> 12
            is ConnEnd.NetworkLost -> 13
            is ConnEnd.LocalAddressGone -> 14
            is ConnEnd.NoUsablePath -> 15
            is ConnEnd.HandshakeSendRefused -> 16
            is ConnEnd.AccessDenied -> 17
        }

    /** The public variant each end must map to, numbered as [variant] numbers ends. */
    private fun publicVariant(end: QuicConnectionEnd): Int =
        when (end) {
            is QuicConnectionEnd.ByPeer -> 0
            is QuicConnectionEnd.ByLocal -> 1
            is QuicConnectionEnd.IdleTimeout -> 2
            is QuicConnectionEnd.HandshakeTimedOut -> 3
            is QuicConnectionEnd.SocketFailed -> 4
            is QuicConnectionEnd.ProcessingFailed -> 5
            is QuicConnectionEnd.CallerFailed -> 6
            is QuicConnectionEnd.LoopFailed -> 7
            is QuicConnectionEnd.LoopStopped -> 8
            is QuicConnectionEnd.ServerClosed -> 9
            is QuicConnectionEnd.ServerClosedUnsettled -> 10
            is QuicConnectionEnd.NoErrorRecorded -> 11
            is QuicConnectionEnd.Released -> 12
            is QuicConnectionEnd.NetworkLost -> 13
            is QuicConnectionEnd.LocalAddressGone -> 14
            is QuicConnectionEnd.NoUsablePath -> 15
            is QuicConnectionEnd.HandshakeSendRefused -> 16
            is QuicConnectionEnd.AccessDenied -> 17
            is QuicConnectionEnd.ReasonOnly -> -1
        }

    @Test
    fun everyEndIsCovered() {
        assertEquals((0 until samples.size).toList(), samples.map(::variant))
    }

    @Test
    fun eachEndMapsToExactlyOnePublicEndOfTheSameName() {
        for (end in samples) {
            val public = publicEndOf(end)
            assertEquals(variant(end), publicVariant(public), "$end mapped to $public")
            assertEquals(end::class.simpleName, public::class.simpleName)
        }
    }

    @Test
    fun theCloseExceptionCarriesThePublicEndAndAReasonDerivedFromIt() {
        for (end in samples) {
            val thrown = assertIs<QuicCloseException>(endedWith(end, QuicCloseCircumstance.Ended))
            assertEquals(variant(end), publicVariant(thrown.end))
            assertEquals(thrown.end.closeReason, thrown.closeReason)
            assertEquals(closeReasonOf(end), thrown.closeReason)
        }
    }

    @Test
    fun aDenialCrossesWholeAndTheCloseNamesTheAccessAndItsRemedy() {
        val thrown =
            assertIs<QuicCloseException>(
                endedWith(ConnEnd.AccessDenied(QuicAccessDenial.MacosNoRouteOnLink), QuicCloseCircumstance.HandshakeFailed),
            )
        assertSame(QuicAccessDenial.MacosNoRouteOnLink, assertIs<QuicConnectionEnd.AccessDenied>(thrown.end).denial)
        assertEquals(QuicCloseReason.Unspecified, thrown.closeReason, "no CONNECTION_CLOSE was exchanged and nothing timed out")
        val message = thrown.message.orEmpty()
        assertTrue(NetworkAccess.LocalNetwork.description in message, message)
        assertTrue(QuicAccessDenial.MacosNoRouteOnLink.remedy in message, message)
    }

    @Test
    fun theFieldsThatExplainAnEndCrossToThePublicType() {
        val timedOut = assertIs<QuicConnectionEnd.HandshakeTimedOut>(publicEndOf(ConnEnd.HandshakeTimedOut(progress)))
        assertEquals(900.milliseconds, timedOut.progress.waited)
        assertEquals(QuicRttSample.Sampled(12.milliseconds), timedOut.progress.firstSample)
        assertEquals(333.milliseconds, timedOut.progress.prior)
        assertEquals(LinkClass.Wifi, timedOut.progress.link)
        assertEquals(3, timedOut.progress.probes)
        assertEquals(QuicHandshakeReached.PeerAnswered, timedOut.progress.reached)

        val refused =
            assertIs<QuicConnectionEnd.HandshakeSendRefused>(
                publicEndOf(ConnEnd.HandshakeSendRefused(progress, SendRefusal.HostUnreachable)),
            )
        assertEquals(QuicHandshakeReached.PeerAnswered, refused.progress.reached)
        assertEquals(QuicSendRefusal.HostUnreachable, refused.refusal)
        assertSame(
            boom,
            assertIs<QuicSendRefusal.Unnamed>(
                assertIs<QuicConnectionEnd.HandshakeSendRefused>(
                    publicEndOf(ConnEnd.HandshakeSendRefused(progress, SendRefusal.Unnamed(Thrown(boom)))),
                ).refusal,
            ).thrown,
        )

        val socket = assertIs<QuicConnectionEnd.SocketFailed>(publicEndOf(ConnEnd.SocketFailed(SocketFault.Repeated(8, Thrown(boom)))))
        val repeated = assertIs<QuicSocketFault.Repeated>(socket.fault)
        assertEquals(8, repeated.count)
        assertSame(boom, repeated.thrown)
        assertIs<QuicSocketFault.Closed>(
            assertIs<QuicConnectionEnd.SocketFailed>(publicEndOf(ConnEnd.SocketFailed(SocketFault.Closed))).fault,
        )

        val loop = assertIs<QuicConnectionEnd.LoopFailed>(publicEndOf(ConnEnd.LoopFailed(Thrown(boom))))
        assertSame(boom, loop.thrown)
        assertSame(boom, (endedWith(ConnEnd.LoopFailed(Thrown(boom)), QuicCloseCircumstance.Ended)).cause)
    }

    @Test
    fun theReasonsCallersAlreadyMatchOnAreUnchanged() {
        assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), closeReasonOf(ConnEnd.IdleTimeout))
        assertEquals(
            QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(900.milliseconds)),
            closeReasonOf(ConnEnd.HandshakeTimedOut(progress)),
        )
        assertEquals(QuicCloseReason.ByLocal(QuicError.NoError), closeReasonOf(ConnEnd.ServerClosed))
        assertEquals(QuicCloseReason.ByLocal(QuicError.NoViablePath), closeReasonOf(ConnEnd.NoUsablePath))
        assertEquals(QuicCloseReason.Unspecified, closeReasonOf(ConnEnd.NetworkLost))
    }

    @Test
    fun aCloseNamedOnlyByItsReasonReportsReasonOnly() {
        val reason = QuicCloseReason.ByPeer(QuicError.ProtocolViolation)
        val thrown = QuicCloseException(reason, QuicCloseCircumstance.Ended)
        assertEquals(QuicConnectionEnd.ReasonOnly(reason), thrown.end)
        assertSame(reason, thrown.closeReason)
    }
}
