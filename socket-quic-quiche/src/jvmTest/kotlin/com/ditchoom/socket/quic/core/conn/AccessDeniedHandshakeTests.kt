package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.testing.ConnRig
import com.ditchoom.socket.quic.core.testing.FakeIo
import com.ditchoom.socket.quic.core.time.WaitResolution
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/**
 * A handshake the system refuses every send of, as it refuses an app the local network, ends in the pass after the
 * first refusal, not at its bound: virtual time, a scripted quiche and a scripted socket, documentation addresses.
 */
class AccessDeniedHandshakeTests {
    private val ms = 1_000_000L
    private val bound = 10_000 * ms
    private val api = StubQuicheApi().apply { established = false }
    private val onLink = PathKey(4, 4433, 0L, 0xC000_0207L) // 192.0.2.7
    private val internet = PathKey(4, 4433, 0L, 0xCB00_7109L) // 203.0.113.9
    private val attached = { listOf(AttachedPrefix(PathKey(4, 0, 0L, 0xC000_0200L), 24)) }

    private fun refusing(why: UdpError) = FakeIo().apply { onSend = { SendBatchResult.Partial(0, why) } }

    private fun rig(
        access: AccessGuard,
        peer: PathKey,
        io: FakeIo,
    ) = ConnRig(
        api,
        rules =
            SlotRules.forWaits(
                WaitResolution(ms),
                HandshakeRule.adaptive(80 * ms, 25 * ms, HandshakeBound.Bounded(bound), LinkClass.Wifi),
                SlotRules.Default.close,
                mismatch = MismatchResponse.Fail,
            ),
        io = io,
        access = access,
        peer = peer,
    )

    /** Runs passes a millisecond apart from 0 until the connection ends or [until]; the time it ended at. */
    private fun ConnRig.runUntilEnded(until: Long): Long {
        var now = 0L
        this@AccessDeniedHandshakeTests.api.connSendQueue.addLast(1200)
        pass(now)
        while (sink.ended.isEmpty() && now < until) {
            now += ms
            pass(now)
        }
        return now
    }

    @Test
    fun aFirstFlightRefusedAsNotPermittedWithThePermissionWithheldEndsInThePassAfterIt() {
        val rig = rig(AccessGuard.AndroidPermission({ PermissionRead.Withheld }, attached), onLink, refusing(UdpError.PermissionDenied))
        val endedAt = rig.runUntilEnded(bound)

        val end = assertIs<ConnEnd.AccessDenied>(rig.sink.ended.single())
        assertSame(QuicAccessDenial.AndroidPermissionNotHeld, end.denial)
        assertEquals(1 * ms, endedAt, "ended one pass after the refused first flight, ${endedAt / ms} ms in, not at the bound")
    }

    @Test
    fun aFirstFlightRefusedWithNoRouteToAnOnLinkPeerOnMacosEndsAsLikelyLocalNetworkPrivacy() {
        val rig = rig(AccessGuard.MacosPrivacy(attached), onLink, refusing(UdpError.HostUnreachable))
        val endedAt = rig.runUntilEnded(bound)

        assertSame(QuicAccessDenial.MacosNoRouteOnLink, assertIs<ConnEnd.AccessDenied>(rig.sink.ended.single()).denial)
        assertEquals(1 * ms, endedAt)
    }

    @Test
    fun noRouteToAPeerOffTheLinkIsNotAPermissionProblemAndEndsAtTheBoundAsToday() {
        val rig = rig(AccessGuard.MacosPrivacy(attached), internet, refusing(UdpError.HostUnreachable))
        api.connTimeout = 250.milliseconds
        val endedAt = rig.runUntilEnded(2 * bound)

        val end = assertIs<ConnEnd.HandshakeSendRefused>(rig.sink.ended.single())
        assertSame(SendRefusal.HostUnreachable, end.refusal)
        assertEquals(bound, endedAt)
    }

    @Test
    fun aRefusalOnAPlatformWithNoGuardEndsAtTheBoundAsToday() {
        val rig = rig(AccessGuard.Unguarded, onLink, refusing(UdpError.PermissionDenied))
        api.connTimeout = 250.milliseconds
        val endedAt = rig.runUntilEnded(2 * bound)

        assertIs<ConnEnd.HandshakeSendRefused>(rig.sink.ended.single())
        assertEquals(bound, endedAt)
    }

    @Test
    fun aRefusalAfterTheHandshakeCompletedIsNotAConnectFailure() {
        val io = FakeIo()
        val rig = rig(AccessGuard.AndroidPermission({ PermissionRead.Withheld }, attached), onLink, io)
        api.connTimeout = 250.milliseconds
        api.connSendQueue.addLast(1200)
        rig.pass(0)
        api.established = true
        var now = 0L
        while (rig.sink.established.isEmpty() && now < bound) {
            now += 50 * ms
            rig.pass(now)
        }
        assertEquals(1, rig.sink.established.size)

        io.onSend = { SendBatchResult.Partial(0, UdpError.PermissionDenied) }
        val refusedFrom = now
        while (now < refusedFrom + 2 * bound) {
            now += 50 * ms
            api.connSendQueue.addLast(1200)
            rig.pass(now)
        }
        assertEquals(emptyList(), rig.sink.ended, "the established connection is left to loss recovery and its idle timeout")
    }

    /** The phase decides, not the record: a handshake that completed is never judged, whatever its sends came to. */
    @Test
    fun aCompletedHandshakeIsNotJudgedEvenWithEverySendRefused() {
        val rig = rig(AccessGuard.AndroidPermission({ PermissionRead.Withheld }, attached), onLink, refusing(UdpError.PermissionDenied))
        api.connTimeout = 250.milliseconds
        api.established = true
        var now = 0L
        while (now < 2 * bound) {
            api.connSendQueue.addLast(1200)
            rig.pass(now)
            now += 50 * ms
        }
        assertEquals(1, rig.sink.established.size)
        assertEquals(emptyList(), rig.sink.ended)
    }

    @Test
    fun aHandshakeTheNetworkTookADatagramOfIsNotDeniedByALaterRefusal() {
        val io = FakeIo()
        val rig = rig(AccessGuard.AndroidPermission({ PermissionRead.Withheld }, attached), onLink, io)
        api.connTimeout = 250.milliseconds
        api.connSendQueue.addLast(1200)
        rig.pass(0)
        io.onSend = { SendBatchResult.Partial(0, UdpError.PermissionDenied) }
        var now = 0L
        while (rig.sink.ended.isEmpty() && now < 2 * bound) {
            now += 50 * ms
            api.connSendQueue.addLast(1200)
            rig.pass(now)
        }
        assertIs<ConnEnd.HandshakeTimedOut>(rig.sink.ended.single(), "the peer was sent to, so the bound reports a silent peer")
        assertEquals(bound, now)
    }
}
