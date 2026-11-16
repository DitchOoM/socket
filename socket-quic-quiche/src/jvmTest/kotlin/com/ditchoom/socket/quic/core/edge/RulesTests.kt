package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.HandshakePacing
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.LinkClass
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicheConfig
import com.ditchoom.socket.quic.RttPrior
import com.ditchoom.socket.quic.StubQuicheApi
import com.ditchoom.socket.quic.core.conn.HandshakeBound
import com.ditchoom.socket.quic.core.conn.HandshakeRule
import com.ditchoom.socket.quic.core.conn.PtoRule
import com.ditchoom.socket.quic.core.testing.FlightEvidenceRule
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Options and the network monitor's link become the handshake rule a connection is built with. */
class RulesTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    private val ms = 1_000_000L
    private val defaults = QuicOptions(alpnProtocols = listOf("test"))

    private fun adaptive(rule: HandshakeRule) = assertIs<PtoRule.Adaptive>(rule.pto)

    @Test
    fun eachLinkClassHasItsPriorAndQuichesFirstPtoIsTheFirstCeiling() {
        val expected =
            mapOf(
                LinkClass.Ethernet to 40L,
                LinkClass.Wifi to 80L,
                LinkClass.Cellular to 150L,
                LinkClass.Satellite to 700L,
                LinkClass.Unknown to 333L,
            )
        for ((link, priorMs) in expected) {
            val rule = clientHandshakeRule(defaults, link)
            assertEquals(priorMs * ms, adaptive(rule).priorNanos, "$link")
            assertEquals(3 * priorMs * ms, adaptive(rule).firstCeilingNanos, "$link")
            assertEquals(link, rule.link)
        }
    }

    @Test
    fun theMonitorsNetworkKindChoosesTheClass() {
        assertEquals(LinkClass.Ethernet, linkClassOf(NetworkId.Link(NetworkKind.Ethernet, 1)))
        assertEquals(LinkClass.Wifi, linkClassOf(NetworkId.KindOnly(NetworkKind.Wifi)))
        assertEquals(LinkClass.Cellular, linkClassOf(NetworkId.Link(NetworkKind.Cellular, 2)))
        assertEquals(LinkClass.Unknown, linkClassOf(NetworkId.Link(NetworkKind.Vpn(setOf(NetworkKind.Wifi)), 3)))
        assertEquals(LinkClass.Unknown, linkClassOf(NetworkId.KindOnly(NetworkKind.Other("x"))))
        assertEquals(LinkClass.Unknown, linkClassOf(NetworkId.Unidentified))
    }

    @Test
    fun theConsumersPriorOverridesTheLink() {
        val satellite = defaults.copy(handshakePacing = HandshakePacing.Adaptive(prior = RttPrior.OfLink(LinkClass.Satellite)))
        val stated = clientHandshakeRule(satellite, LinkClass.Wifi)
        assertEquals(700 * ms, adaptive(stated).priorNanos)
        assertEquals(LinkClass.Satellite, stated.link)
        val known = defaults.copy(handshakePacing = HandshakePacing.Adaptive(prior = RttPrior.Known(20.milliseconds)))
        val rule = clientHandshakeRule(known, LinkClass.Cellular)
        assertEquals(20 * ms, adaptive(rule).priorNanos)
        assertEquals(60 * ms, adaptive(rule).firstCeilingNanos)
        assertEquals(LinkClass.Cellular, rule.link)
    }

    @Test
    fun theFloorRaisesASmallPriorToAThirdOfItAndBoundsTheFirstCeiling() {
        val lan = defaults.copy(handshakePacing = HandshakePacing.Adaptive(prior = RttPrior.Known(1.milliseconds)))
        val rule = adaptive(clientHandshakeRule(lan, LinkClass.Ethernet))
        assertEquals(25 * ms / 3, rule.priorNanos)
        assertEquals(25 * ms, rule.firstCeilingNanos)
    }

    @Test
    fun aServerUsesTheRfcPriorUnlessOneIsKnown() {
        assertEquals(333 * ms, adaptive(serverHandshakeRule(defaults)).priorNanos)
        assertEquals(LinkClass.Unknown, serverHandshakeRule(defaults).link)
        val known = defaults.copy(handshakePacing = HandshakePacing.Adaptive(prior = RttPrior.Known(50.milliseconds)))
        assertEquals(50 * ms, adaptive(serverHandshakeRule(known)).priorNanos)
    }

    @Test
    fun theDefaultBoundIsTenSecondsAndUntilClosedHasNone() {
        assertEquals(10_000 * ms, assertIs<HandshakeBound.Bounded>(clientHandshakeRule(defaults, LinkClass.Wifi).bound).nanos)
        val open = defaults.copy(handshakePolicy = HandshakePolicy.UntilClosed)
        assertIs<HandshakeBound.UntilClosed>(clientHandshakeRule(open, LinkClass.Wifi).bound)
        val short = defaults.copy(handshakePolicy = HandshakePolicy.Bounded(3.seconds))
        assertEquals(3_000 * ms, assertIs<HandshakeBound.Bounded>(serverHandshakeRule(short).bound).nanos)
    }

    @Test
    fun theRuleGivesQuicheItsInitialRttAndRfc9002KeepsQuichesOwn() {
        val api = StubQuicheApi()
        clientHandshakeRule(defaults, LinkClass.Wifi).applyTo(api, QuicheConfig(1L))
        val rfc = defaults.copy(handshakePacing = HandshakePacing.Rfc9002)
        val rule = clientHandshakeRule(rfc, LinkClass.Wifi)
        rule.applyTo(api, QuicheConfig(1L))
        assertIs<PtoRule.Rfc9002>(rule.pto)
        assertEquals(333 * ms, rule.priorNanos())
        assertEquals(listOf(80 * ms), api.initialRtts)
    }
}
