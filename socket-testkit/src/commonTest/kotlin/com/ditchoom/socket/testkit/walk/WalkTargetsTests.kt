package com.ditchoom.socket.testkit.walk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A walk pinned to one server address confounds address family with device: through 2026-09 the
 * iPhone walked `2a01:4ff:f4:eb1a::1` and the Samsung `178.156.248.95`, so every difference between
 * the two recordings could be either the family or the phone. Rotating the target per connection
 * puts both families on one device and one route.
 */
class WalkTargetsTests {
    private val v4 = WalkTarget("178.156.248.95", 44433)
    private val v6 = WalkTarget("2a01:4ff:f4:eb1a::1", 44433)

    @Test
    fun theFamilyComesFromTheLiteral() {
        assertEquals(AddressFamily.V4, AddressFamily.of("178.156.248.95"))
        assertEquals(AddressFamily.V6, AddressFamily.of("2a01:4ff:f4:eb1a::1"))
        assertEquals(AddressFamily.V6, AddressFamily.of("::1"))
    }

    /** A name resolves inside the platform builder (#615), so its family is not the probe's to claim. */
    @Test
    fun aNameHasNoFamilyTheProbeCanName() {
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("quic-echo.example.com"))
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("localhost"))
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("178.156.248"), "three octets are not a literal")
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("178.156.248.300"), "300 is not an octet")
    }

    @Test
    fun rotationAlternatesPerAttemptAndWrapsAround() {
        val targets = WalkTargets(v4, listOf(v6))
        assertEquals(listOf(v4, v6, v4, v6, v4), (1..5).map { targets.forAttempt(it) })
    }

    @Test
    fun aThirdTargetTakesItsTurnInOrder() {
        val other = WalkTarget("2a01:4ff:f4:eb1a::2", 44433)
        val targets = WalkTargets(v4, listOf(v6, other))
        assertEquals(listOf(v4, v6, other, v4, v6, other), (1..6).map { targets.forAttempt(it) })
    }

    /** One host is what every walk before this one did, and it has to keep behaving that way. */
    @Test
    fun oneTargetIsEveryAttempt() {
        val targets = WalkTargets(v4)
        assertEquals(List(7) { v4 }, (1..7).map { targets.forAttempt(it) })
        assertEquals("targets=178.156.248.95:44433/v4", targets.line)
    }

    @Test
    fun anIpv6LiteralIsBracketedSoItsColonsAreNotReadAsThePort() {
        assertEquals("[2a01:4ff:f4:eb1a::1]:44433", v6.authority)
        assertEquals("178.156.248.95:44433", v4.authority)
        assertEquals("target=[2a01:4ff:f4:eb1a::1]:44433 family=v6", v6.line)
        assertEquals("target=178.156.248.95:44433 family=v4", v4.line)
    }

    /** Every migration, verdict and liveness line has to say which family it happened on. */
    @Test
    fun aConnectionTagCarriesTheFamilyItHappenedOn() {
        assertEquals("connection=7 family=v6", v6.connectionTag(7))
        assertEquals("connection=7 family=v4", v4.connectionTag(7))
        assertEquals("connection=1 family=resolver", WalkTarget("quic-echo.example.com", 44433).connectionTag(1))
    }

    @Test
    fun aCommaSeparatedArgumentKeepsTheOrderItWasGivenAndTheColonsInALiteral() {
        val parsed = WalkTargets.parse("178.156.248.95,2a01:4ff:f4:eb1a::1", 44433)
        val targets = assertIs<WalkTargetsParse.Rotation>(parsed).targets
        assertEquals(listOf(v4, v6), targets.all)
        assertEquals("targets=178.156.248.95:44433/v4,[2a01:4ff:f4:eb1a::1]:44433/v6", targets.line)
    }

    @Test
    fun oneHostParsesToOneTarget() {
        val targets = assertIs<WalkTargetsParse.Rotation>(WalkTargets.parse("178.156.248.95", 44433)).targets
        assertEquals(listOf(v4), targets.all)
        assertEquals(v4, targets.first)
    }

    /** Whitespace around a host survives a shell that quoted the list as one word. */
    @Test
    fun surroundingSpaceIsNotPartOfAHost() {
        val targets = assertIs<WalkTargetsParse.Rotation>(WalkTargets.parse(" 178.156.248.95 , 2a01:4ff:f4:eb1a::1 ", 44433)).targets
        assertEquals(listOf(v4, v6), targets.all)
    }

    @Test
    fun anEmptyArgumentNamesNoHostRatherThanGuessingOne() {
        assertEquals(WalkTargetsParse.NoHost, WalkTargets.parse("", 44433))
        assertEquals(WalkTargetsParse.NoHost, WalkTargets.parse("  ", 44433))
        assertEquals(WalkTargetsParse.NoHost, WalkTargets.parse(",", 44433))
    }

    @Test
    fun theSeparatorIsNotAColonBecauseALiteralIsFullOfThem() {
        assertTrue(v6.host.contains(':'), "the v6 literal this rig walks: ${v6.host}")
        assertEquals(',', WalkTargets.SEPARATOR)
    }
}
