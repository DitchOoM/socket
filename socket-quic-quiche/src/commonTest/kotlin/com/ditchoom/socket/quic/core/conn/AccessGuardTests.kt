package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.NetworkAccess
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicAccessDenial
import com.ditchoom.socket.quic.QuicAccessRemedy
import com.ditchoom.socket.quic.core.sched.SendRecord
import com.ditchoom.socket.quic.core.udp.AttachedPrefix
import com.ditchoom.socket.quic.core.udp.SendRefusal
import com.ditchoom.socket.quic.core.udp.Thrown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** What a refused connect or handshake says about an app's access, per platform guard, on documentation ranges. */
class AccessGuardTests {
    private val onLink = PathKey(4, 4433, 0L, 0xC000_0207L) // 192.0.2.7
    private val privateRouted = PathKey(4, 4433, 0L, 0x0A01_0203L) // 10.1.2.3, not attached
    private val internet = PathKey(4, 4433, 0L, 0xCB00_7109L) // 203.0.113.9
    private val loopback = PathKey(4, 4433, 0L, 0x7F00_0001L)
    private val attached = { listOf(AttachedPrefix(PathKey(4, 0, 0L, 0xC000_0200L), 24)) }

    private fun android(permission: PermissionRead) = AccessGuard.AndroidPermission({ permission }, attached)

    private val macos = AccessGuard.MacosPrivacy(attached)

    private fun denial(verdict: AccessVerdict): QuicAccessDenial = assertIs<AccessVerdict.Denied>(verdict).denial

    /** A handshake to [peer] whose every send was refused, the last for [refusal]. */
    private fun AccessGuard.sends(
        refusal: SendRefusal,
        peer: PathKey,
    ): AccessVerdict = sendsAccess(peer).judge(SendRecord.Refused(refusal))

    private fun AccessGuard.probe(
        refusal: SendRefusal,
        peer: PathKey,
    ): AccessVerdict = judgeProbe(refusal, peer, ProbeTurn.First)

    @Test
    fun androidWithAPermissionWithheldReadsARefusedSendToTheLocalNetworkAsTheDenial() {
        val withheld = android(PermissionRead.Withheld)
        for (peer in listOf(onLink, privateRouted)) {
            assertSame(QuicAccessDenial.AndroidPermissionNotHeld, denial(withheld.sends(SendRefusal.PermissionDenied, peer)))
            assertSame(QuicAccessDenial.AndroidPermissionNotHeld, denial(withheld.probe(SendRefusal.PermissionDenied, peer)))
        }
    }

    /** Data Saver, a background firewall chain and VPN lockdown refuse sends as not permitted too. */
    @Test
    fun androidWithAPermissionWithheldDoesNotBlameItForARefusedSendToLoopbackOrTheInternet() {
        for (peer in listOf(loopback, internet)) {
            assertIs<AccessVerdict.NotDenied>(android(PermissionRead.Withheld).sends(SendRefusal.PermissionDenied, peer), "$peer")
            assertIs<AccessVerdict.NotDenied>(android(PermissionRead.Withheld).probe(SendRefusal.PermissionDenied, peer), "$peer")
        }
    }

    @Test
    fun androidWithThePermissionHeldReadsARefusedSendAsSomethingElse() {
        assertIs<AccessVerdict.NotDenied>(android(PermissionRead.Held).sends(SendRefusal.PermissionDenied, onLink))
    }

    @Test
    fun androidThatCannotReadItsPermissionCallsARefusalToTheLocalNetworkLikely() {
        val unread = android(PermissionRead.Unread)
        assertSame(QuicAccessDenial.AndroidSendsNotPermitted, denial(unread.sends(SendRefusal.PermissionDenied, onLink)))
        assertSame(QuicAccessDenial.AndroidSendsNotPermitted, denial(unread.sends(SendRefusal.PermissionDenied, privateRouted)))
        assertIs<AccessVerdict.NotDenied>(unread.sends(SendRefusal.PermissionDenied, internet))
        assertIs<AccessVerdict.NotDenied>(unread.sends(SendRefusal.PermissionDenied, loopback))
    }

    @Test
    fun androidJudgesOnlyARefusalAsNotPermitted() {
        val withheld = android(PermissionRead.Withheld)
        for (refusal in otherThan(SendRefusal.PermissionDenied)) {
            assertIs<AccessVerdict.NotDenied>(withheld.sends(refusal, onLink), "$refusal")
        }
    }

    /** The interfaces are listed only for an address its range does not already place. */
    @Test
    fun androidListsTheInterfacesOnlyForAnAddressItsRangeDoesNotPlace() {
        var listed = 0
        val guard = AccessGuard.AndroidPermission({ PermissionRead.Withheld }, { attached().also { listed++ } })
        for (peer in listOf(loopback, privateRouted, PathKey(4, 4433, 0L, 0xA9FE_0001L))) guard.sendsAccess(peer)
        assertEquals(0, listed)
        guard.sendsAccess(onLink)
        assertEquals(1, listed)
    }

    @Test
    fun macosReadsNoRouteOnTheFirstProbeToAnOnLinkPeerAsLikelyLocalNetworkPrivacy() {
        for (refusal in listOf(SendRefusal.HostUnreachable, SendRefusal.NetworkUnreachable)) {
            val denied = denial(macos.probe(refusal, onLink))
            assertSame(QuicAccessDenial.MacosNoRouteOnLink, denied)
            assertIs<QuicAccessDenial.Likely>(denied)
            assertEquals(NetworkAccess.LocalNetwork, denied.access)
        }
    }

    @Test
    fun macosDoesNotJudgeALaterProbeToTheSamePeer() {
        assertIs<AccessVerdict.NotDenied>(macos.judgeProbe(SendRefusal.HostUnreachable, onLink, ProbeTurn.Again))
    }

    /** A neighbour that stopped answering draws the same refusal of a new socket's first sends for a while. */
    @Test
    fun macosNeverReadsARefusedSendAsLocalNetworkPrivacy() {
        for (refusal in everyRefusal()) {
            assertIs<AccessVerdict.NotDenied>(macos.sends(refusal, onLink), "$refusal")
        }
    }

    @Test
    fun macosDoesNotCallNoRouteToAPeerOffTheLinkAPermissionProblem() {
        assertIs<AccessVerdict.NotDenied>(macos.probe(SendRefusal.HostUnreachable, internet))
        assertIs<AccessVerdict.NotDenied>(macos.probe(SendRefusal.HostUnreachable, privateRouted), "a private range behind a router")
        assertIs<AccessVerdict.NotDenied>(macos.probe(SendRefusal.HostUnreachable, loopback))
    }

    @Test
    fun macosJudgesOnlyTheNoRouteFamily() {
        for (refusal in everyRefusal().filter { it != SendRefusal.HostUnreachable && it != SendRefusal.NetworkUnreachable }) {
            assertIs<AccessVerdict.NotDenied>(macos.probe(refusal, onLink), "$refusal")
        }
    }

    @Test
    fun aPeerIsProbedForTheFirstTimeOnceWhateverItsPortAndOnlyWhileTheRecordHasRoom() {
        val probed = ProbedPeers(limit = 2)
        assertEquals(ProbeTurn.First, probed.probing(onLink))
        assertEquals(ProbeTurn.Again, probed.probing(onLink.copy(port = 443)))
        assertEquals(ProbeTurn.First, probed.probing(internet))
        assertEquals(ProbeTurn.Again, probed.probing(privateRouted), "a full record judges no more peers")
    }

    @Test
    fun anUnguardedPlatformDeniesNothing() {
        for (refusal in everyRefusal()) {
            assertIs<AccessVerdict.NotDenied>(AccessGuard.Unguarded.sends(refusal, onLink))
            assertIs<AccessVerdict.NotDenied>(AccessGuard.Unguarded.probe(refusal, onLink))
        }
    }

    @Test
    fun onlyAHandshakeWhoseEverySendWasRefusedIsJudged() {
        val access = android(PermissionRead.Withheld).sendsAccess(onLink)
        assertIs<AccessVerdict.NotDenied>(access.judge(SendRecord.Quiet))
        assertIs<AccessVerdict.NotDenied>(access.judge(SendRecord.Taking), "the network took a datagram: the peer was reached")
        assertIs<AccessVerdict.Denied>(access.judge(SendRecord.Refused(SendRefusal.PermissionDenied)))
    }

    /** One vocabulary: every denial names its access and a typed remedy, and the type says how sure it is. */
    @Test
    fun everyDenialNamesItsAccessARemedyAndHowItIsKnown() {
        val all =
            listOf(
                QuicAccessDenial.AndroidPermissionNotHeld to QuicAccessRemedy.RequestAndroidLocalNetworkPermission,
                QuicAccessDenial.AndroidSendsNotPermitted to QuicAccessRemedy.RequestAndroidLocalNetworkPermission,
                QuicAccessDenial.AppleSettings(NetworkAccess.LocalNetwork) to QuicAccessRemedy.AllowLocalNetworkInAppleSettings,
                QuicAccessDenial.AppleSettings(NetworkAccess.CellularData) to QuicAccessRemedy.AllowCellularDataInAppleSettings,
                QuicAccessDenial.AppleSettings(NetworkAccess.WifiData) to QuicAccessRemedy.AllowWifiDataInAppleSettings,
                QuicAccessDenial.MacosNoRouteOnLink to QuicAccessRemedy.AllowLocalNetworkInMacosSettings,
            )
        for ((denial, remedy) in all) {
            val reported =
                when (denial) {
                    is QuicAccessDenial.AndroidPermissionNotHeld, is QuicAccessDenial.AppleSettings -> true
                    is QuicAccessDenial.AndroidSendsNotPermitted, is QuicAccessDenial.MacosNoRouteOnLink -> false
                }
            assertEquals(reported, denial is QuicAccessDenial.Reported, "$denial")
            assertEquals(!reported, denial is QuicAccessDenial.Likely, "$denial")
            assertEquals(remedy, denial.remedy, "$denial")
        }
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
