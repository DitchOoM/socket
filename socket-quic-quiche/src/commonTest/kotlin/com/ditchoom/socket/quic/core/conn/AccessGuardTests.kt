package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a refused connect or handshake says about an app's access, per platform guard, on documentation ranges. */
class AccessGuardTests {
    private val onLink = PathKey(4, 4433, 0L, 0xC000_0207L) // 192.0.2.7
    private val privateRouted = PathKey(4, 4433, 0L, 0x0A01_0203L) // 10.1.2.3, not attached
    private val internet = PathKey(4, 4433, 0L, 0xCB00_7109L) // 203.0.113.9
    private val attached = { listOf(AttachedPrefix(PathKey(4, 0, 0L, 0xC000_0200L), 24)) }

    private fun android(permission: PermissionRead) = AccessGuard.AndroidPermission({ permission }, attached)

    private val macos = AccessGuard.MacosPrivacy(attached)

    private fun denial(verdict: AccessVerdict): QuicAccessDenial = assertIs<AccessVerdict.Denied>(verdict).denial

    @Test
    fun androidWithAPermissionWithheldReadsARefusedSendAsTheDenialWhereverThePeerIs() {
        for (peer in listOf(onLink, privateRouted, internet)) {
            assertSame(
                QuicAccessDenial.AndroidPermissionNotHeld,
                denial(android(PermissionRead.Withheld).judge(SendRefusal.PermissionDenied, peer)),
            )
        }
    }

    @Test
    fun androidWithThePermissionHeldReadsARefusedSendAsSomethingElse() {
        assertIs<AccessVerdict.NotDenied>(android(PermissionRead.Held).judge(SendRefusal.PermissionDenied, onLink))
    }

    @Test
    fun androidThatCannotReadItsPermissionCallsARefusalToTheLocalNetworkLikely() {
        val unread = android(PermissionRead.Unread)
        assertSame(QuicAccessDenial.AndroidSendsNotPermitted, denial(unread.judge(SendRefusal.PermissionDenied, onLink)))
        assertSame(QuicAccessDenial.AndroidSendsNotPermitted, denial(unread.judge(SendRefusal.PermissionDenied, privateRouted)))
        assertIs<AccessVerdict.NotDenied>(unread.judge(SendRefusal.PermissionDenied, internet))
    }

    @Test
    fun androidJudgesOnlyARefusalAsNotPermitted() {
        val withheld = android(PermissionRead.Withheld)
        for (refusal in otherThan(SendRefusal.PermissionDenied)) {
            assertIs<AccessVerdict.NotDenied>(withheld.judge(refusal, onLink), "$refusal")
        }
    }

    @Test
    fun macosReadsNoRouteToAnOnLinkPeerAsLikelyLocalNetworkPrivacy() {
        val denied = denial(macos.judge(SendRefusal.HostUnreachable, onLink))
        assertSame(QuicAccessDenial.MacosNoRouteOnLink, denied)
        assertIs<QuicAccessDenial.Likely>(denied)
        assertEquals(NetworkAccess.LocalNetwork, denied.access)
    }

    @Test
    fun macosDoesNotCallNoRouteToAPeerOffTheLinkAPermissionProblem() {
        assertIs<AccessVerdict.NotDenied>(macos.judge(SendRefusal.HostUnreachable, internet))
        assertIs<AccessVerdict.NotDenied>(
            macos.judge(SendRefusal.HostUnreachable, privateRouted),
            "a private range behind a router or tunnel",
        )
        assertIs<AccessVerdict.NotDenied>(macos.judge(SendRefusal.HostUnreachable, PathKey(4, 4433, 0L, 0x7F00_0001L)))
    }

    @Test
    fun macosJudgesOnlyTheNoRouteFamily() {
        assertSame(
            QuicAccessDenial.MacosNoRouteOnLink,
            denial(macos.judge(SendRefusal.NetworkUnreachable, onLink)),
            "a JVM reads EHOSTUNREACH so",
        )
        for (refusal in everyRefusal().filter { it != SendRefusal.HostUnreachable && it != SendRefusal.NetworkUnreachable }) {
            assertIs<AccessVerdict.NotDenied>(macos.judge(refusal, onLink), "$refusal")
        }
    }

    @Test
    fun anUnguardedPlatformDeniesNothing() {
        for (refusal in everyRefusal()) {
            assertIs<AccessVerdict.NotDenied>(AccessGuard.Unguarded.judge(refusal, onLink))
        }
    }

    @Test
    fun onlyAHandshakeWhoseEverySendWasRefusedIsJudged() {
        val peer = PeerAccess(android(PermissionRead.Withheld), onLink)
        assertIs<AccessVerdict.NotDenied>(peer.judge(SendRecord.Quiet))
        assertIs<AccessVerdict.NotDenied>(peer.judge(SendRecord.Taking), "the network took a datagram: the peer was reached")
        assertIs<AccessVerdict.Denied>(peer.judge(SendRecord.Refused(SendRefusal.PermissionDenied)))
    }

    /** One vocabulary: every denial names its access and a remedy, and the type says how sure it is. */
    @Test
    fun everyDenialNamesItsAccessARemedyAndHowItIsKnown() {
        val all =
            listOf(
                QuicAccessDenial.AndroidPermissionNotHeld,
                QuicAccessDenial.AndroidSendsNotPermitted,
                QuicAccessDenial.AppleSettings(NetworkAccess.LocalNetwork),
                QuicAccessDenial.AppleSettings(NetworkAccess.CellularData),
                QuicAccessDenial.MacosNoRouteOnLink,
            )
        for (denial in all) {
            val reported =
                when (denial) {
                    is QuicAccessDenial.AndroidPermissionNotHeld, is QuicAccessDenial.AppleSettings -> true
                    is QuicAccessDenial.AndroidSendsNotPermitted, is QuicAccessDenial.MacosNoRouteOnLink -> false
                }
            assertEquals(reported, denial is QuicAccessDenial.Reported, "$denial")
            assertEquals(!reported, denial is QuicAccessDenial.Likely, "$denial")
            assertTrue(denial.remedy.isNotBlank(), "$denial")
        }
        assertTrue("ACCESS_LOCAL_NETWORK" in QuicAccessDenial.AndroidPermissionNotHeld.remedy)
        assertTrue("Local Network" in QuicAccessDenial.MacosNoRouteOnLink.remedy)
    }

    private fun otherThan(judged: SendRefusal): List<SendRefusal> = everyRefusal().filter { it != judged }

    private fun everyRefusal(): List<SendRefusal> =
        listOf(
            SendRefusal.TooLarge,
            SendRefusal.NetworkUnreachable,
            SendRefusal.HostUnreachable,
            SendRefusal.PortUnreachable,
            SendRefusal.NoBufferSpace,
            SendRefusal.PermissionDenied,
            SendRefusal.Unnamed(Thrown(IllegalStateException("unnamed"))),
        )
}
