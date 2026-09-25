package com.ditchoom.socket.testkit.walk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * A walk pinned to one server address confounds address family with device, and one connection
 * rotated per attempt tests one family for as long as the network holds still. One lane per target
 * puts every family on one device, one route and every network the device crosses.
 */
class WalkTargetsTests {
    private val v4 = WalkTarget("192.0.2.1", 44433)
    private val v6 = WalkTarget("2001:db8::1", 44433)

    @Test
    fun theFamilyComesFromTheLiteral() {
        assertEquals(AddressFamily.V4, AddressFamily.of("192.0.2.1"))
        assertEquals(AddressFamily.V6, AddressFamily.of("2001:db8::1"))
        assertEquals(AddressFamily.V6, AddressFamily.of("::1"))
    }

    /** A name resolves inside the platform builder (#615), so its family is not the probe's to claim. */
    @Test
    fun aNameHasNoFamilyTheProbeCanName() {
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("quic-echo.example.com"))
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("localhost"))
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("192.0.2"), "three octets are not a literal")
        assertEquals(AddressFamily.ResolverChoice, AddressFamily.of("192.0.2.300"), "300 is not an octet")
    }

    @Test
    fun eachTargetIsALaneNamedByItsFamily() {
        val lanes = WalkTargets(v4, listOf(v6)).lanes
        assertEquals(listOf("v4", "v6"), lanes.map { it.label })
        assertEquals(listOf(v4, v6), lanes.map { it.target })
        assertEquals("lane=v6", lanes[1].token)
    }

    /** Two targets of one family are two lanes, and their lines must not merge. */
    @Test
    fun aRepeatedFamilyGetsANumberedLane() {
        val other = WalkTarget("2001:db8::2", 44433)
        assertEquals(listOf("v4", "v6", "v6-2"), WalkTargets(v4, listOf(v6, other)).lanes.map { it.label })
    }

    @Test
    fun theSecondLaneStartsHalfAnIntervalLater() {
        val lanes = WalkTargets(v4, listOf(v6)).lanes
        assertEquals(listOf(0.milliseconds, 125.milliseconds), lanes.map { it.stagger(250.milliseconds, lanes.size) })
        assertEquals(
            "LANES v4=192.0.2.1:44433 v6=[2001:db8::1]:44433 staggerMs=125",
            WalkTargets(v4, listOf(v6)).lanesLine(250.milliseconds),
        )
    }

    @Test
    fun aLaneLogPutsItsTokenInFrontOfEveryLine() {
        val lines = ArrayList<String>()
        val log = WalkTargets(v4, listOf(v6)).lanes[1].log { lines += it }
        log("ECHO-OK seq=1 rtt=44ms pending=0B")
        assertEquals(listOf("lane=v6 ECHO-OK seq=1 rtt=44ms pending=0B"), lines)
    }

    @Test
    fun aLanesFilesAreNamedByTheLane() {
        assertEquals("conn-v6-0003", WalkTargets(v4, listOf(v6)).lanes[1].fileStem(3))
    }

    /** One host is what every walk before lanes did: one lane, and it has to keep behaving that way. */
    @Test
    fun oneTargetIsOneLane() {
        val targets = WalkTargets(v4)
        assertEquals(listOf("v4"), targets.lanes.map { it.label })
        assertEquals(0.milliseconds, targets.lanes.single().stagger(250.milliseconds, 1))
        assertEquals("targets=192.0.2.1:44433/v4", targets.line)
    }

    @Test
    fun anIpv6LiteralIsBracketedSoItsColonsAreNotReadAsThePort() {
        assertEquals("[2001:db8::1]:44433", v6.authority)
        assertEquals("192.0.2.1:44433", v4.authority)
        assertEquals("target=[2001:db8::1]:44433 family=v6", v6.line)
        assertEquals("target=192.0.2.1:44433 family=v4", v4.line)
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
        val parsed = WalkTargets.parse("192.0.2.1,2001:db8::1", 44433)
        val targets = assertIs<WalkTargetsParse.Parsed>(parsed).targets
        assertEquals(listOf(v4, v6), targets.all)
        assertEquals("targets=192.0.2.1:44433/v4,[2001:db8::1]:44433/v6", targets.line)
    }

    @Test
    fun oneHostParsesToOneTarget() {
        val targets = assertIs<WalkTargetsParse.Parsed>(WalkTargets.parse("192.0.2.1", 44433)).targets
        assertEquals(listOf(v4), targets.all)
        assertEquals(v4, targets.first)
    }

    /** Whitespace around a host survives a shell that quoted the list as one word. */
    @Test
    fun surroundingSpaceIsNotPartOfAHost() {
        val targets = assertIs<WalkTargetsParse.Parsed>(WalkTargets.parse(" 192.0.2.1 , 2001:db8::1 ", 44433)).targets
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
