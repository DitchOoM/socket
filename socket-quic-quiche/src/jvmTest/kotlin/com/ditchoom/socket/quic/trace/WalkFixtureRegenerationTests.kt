package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The walk fixtures in `commonTest/sim/fixtures/` are the output of [TraceToFixture] over the
 * committed windows in `resources/walk-traces/`, byte for byte, or this fails. Each window is
 * [TraceToFixture.extractWindow] over the pulled trace, from the instant in its file name: every
 * line kept, `OS_NET` on the connection's clock, every address a documentation one.
 */
class WalkFixtureRegenerationTests {
    /** Where a fixture's run ends. */
    private sealed interface FixtureEnd {
        /** At the connection's close, which the window holds. */
        data object AtClose : FixtureEnd

        /** At [at] on the connection's clock: the window was cut while the connection was still up. */
        data class At(
            val at: Duration,
        ) : FixtureEnd
    }

    private class Walk(
        val windowResource: String,
        val fixtureName: String,
        val valName: String,
        val fromNanos: Long,
        val fixtureFile: String,
        val end: FixtureEnd = FixtureEnd.AtClose,
    )

    private val walks =
        listOf(
            Walk(
                "walk-traces/20260919T153909Z-walk-0912-0915-conn-0007-from-13066795292.trace",
                "walk-2026-09-12-conn-7-dead-link-handoff",
                "walk20260912Conn7DeadLinkHandoff",
                13_066_795_292L,
                "Walk20260912Conn7DeadLinkHandoff.kt",
            ),
            Walk(
                "walk-traces/20260911T155035Z-walk-2026-09-10-conn-0001-from-1572876724625.trace",
                "walk-2026-09-10-conn-1-dead-link-handoff",
                "walk20260910Conn1DeadLinkHandoff",
                1_572_876_724_625L,
                "Walk20260910Conn1DeadLinkHandoff.kt",
            ),
            Walk(
                "walk-traces/20260924T212145Z-walk-0920-leg3-conn-0001-from-25141684665792.trace",
                "walk-2026-09-20-leg-3-conn-1-downlink-blackout",
                "walk20260920Leg3Conn1DownlinkBlackout",
                25_141_684_665_792L,
                "Walk20260920Leg3Conn1DownlinkBlackout.kt",
            ),
            Walk(
                "walk-traces/20260924T220750Z-walk-0924-short-conn-v4-0001-from-1193636943191.trace",
                "walk-2026-09-24-v4-conn-1-cellular-not-yet-passing",
                "walk20260924V4Conn1CellularNotYetPassing",
                1_193_636_943_191L,
                "Walk20260924V4Conn1CellularNotYetPassing.kt",
            ),
            Walk(
                "walk-traces/20260924T220750Z-walk-0924-short-conn-v4-0002-from-382604493031-to-396000000000.trace",
                "walk-2026-09-24-v4-conn-2-abrupt-handoff",
                "walk20260924V4Conn2AbruptHandoff",
                382_604_493_031L,
                "Walk20260924V4Conn2AbruptHandoff.kt",
                FixtureEnd.At(396_000_000_000L.nanoseconds),
            ),
        )

    private fun Walk.events(): List<TraceEvent> {
        val window = checkNotNull(javaClass.classLoader.getResource(windowResource)) { "missing $windowResource" }
        return TraceEvent.parseAll(window.readText().lines())
    }

    private fun Walk.committedFixture(): File =
        File("src/commonTest/kotlin/com/ditchoom/socket/quic/sim/fixtures/$fixtureFile").also {
            assertTrue(it.isFile, "run from the module directory: ${it.absolutePath} not found")
        }

    private fun regenerate(walk: Walk): String {
        val events = walk.events()
        val from = walk.fromNanos.nanoseconds
        val end =
            when (val end = walk.end) {
                FixtureEnd.AtClose -> events.filterIsInstance<TraceEvent.State>().last { it.name.endsWith("Closed") }.at
                is FixtureEnd.At -> end.at
            }
        return TraceToFixture.generateKotlin(
            walk.fixtureName,
            walk.valName,
            TraceToFixture.window(events, from),
            runFor = end - from,
        )
    }

    @Test
    fun theCommittedWalkFixturesAreTraceToFixturesOutputOverTheCommittedWindows() {
        for (walk in walks) {
            assertEquals(
                regenerate(walk),
                walk.committedFixture().readText(),
                "${walk.fixtureFile} is not TraceToFixture's output over ${walk.windowResource}",
            )
        }
    }

    /**
     * This repository is public, so nothing committed from a walk may name a real address — not in a
     * line's text, and not in a path token, whose hex *is* the phone's local address.
     */
    @Test
    fun everyCommittedWindowAndFixtureCarriesOnlyDocumentationAddresses() {
        for (walk in walks) {
            val events = walk.events()
            val real = events.flatMap(TraceAddressRedaction::addressesIn).filterNot(TraceAddressRedaction::isDocumentation)
            assertEquals(emptyList(), real.distinct(), "${walk.windowResource} names real addresses")
            assertEquals(events, TraceAddressRedaction.redact(events), "${walk.windowResource} is not a redaction fixed point")
            val text = walk.committedFixture().readText()
            val inFixture = TraceAddressRedaction.literalsIn(text).filterNot(TraceAddressRedaction::isDocumentation)
            assertEquals(emptyList(), inFixture.distinct(), "${walk.fixtureFile} names real addresses")
        }
    }

    @Test
    fun redactionIsOneTableAcrossPathTokensHostsLinksAndMessages() {
        val phone = InetAddress.getByName("10.0.0.7")
        val other = InetAddress.getByName("10.0.0.8")
        val v6 = InetAddress.getByName("fd00::7")
        val trace =
            TraceEvent.parseAll(
                listOf(
                    "v1 1 DGRAM_IN 1 ${path(phone)} 00",
                    "v1 2 PATH_STATE Probing 10.0.0.8 4000",
                    "v1 3 DGRAM_OUT 1 ${path(other)} 00",
                    "v1 4 ERROR java.io.IOException to 10.0.0.7 from [fd00::7]:443 via 203.0.113.9",
                    "v1 5 OS_NET state=Offline v4=Absent v6=Absent cell=NotReported links=wlan0=10.0.0.7,fd00::7",
                    "v1 6 DGRAM_IN 1 ${path(v6)} 00",
                ),
            )
        val redacted = TraceAddressRedaction.redact(trace)
        val addresses = redacted.map { TraceAddressRedaction.addressesIn(it) }
        assertTrue(addresses.flatten().all(TraceAddressRedaction::isDocumentation), "left a real address: $redacted")
        // One table: the phone's address is the same documentation address in its path token, in the
        // message and on its link; the other address stays distinct; a documentation address is kept.
        val phoneDoc = addresses[0].single()
        assertEquals(addresses[1], addresses[2], "PATH_STATE host and its path token must redact alike")
        assertTrue(addresses[1].single() != phoneDoc, "two addresses collapsed into one")
        assertEquals(listOf(phoneDoc, addresses[5].single(), InetAddress.getByName("203.0.113.9")), addresses[3])
        assertEquals(listOf(phoneDoc, addresses[5].single()), addresses[4])
        assertTrue(phoneDoc != InetAddress.getByName("203.0.113.9"), "handed out an address the trace already used")
        assertEquals(redacted, TraceAddressRedaction.redact(redacted), "a redacted trace must be a fixed point")
    }

    @Test
    fun aProbeClockOffsetThatIsNotInStepWithTheMonitorIsRefused() {
        val lines =
            sequenceOf(
                "v1 0 OS_NET state=Offline v4=Absent v6=Absent cell=NotReported links=-",
                "v1 1000000000 NET Offline",
                "v1 6001000000 OS_NET state=Offline v4=Absent v6=Absent cell=NotReported links=-",
                "v1 5000000000 NET Routable Link:Cellular:2 Pending",
            )
        val inStep = TraceToFixture.extractWindow(lines, Duration.ZERO, Duration.INFINITE, OsNetStamps.ProbeClock(5.seconds))
        assertEquals(1001.milliseconds, inStep.filterIsInstance<TraceEvent.OsNet>().last().at)
        assertFailsWith<IllegalStateException>("an OS_NET stamped after the monitor moved on was accepted") {
            TraceToFixture.extractWindow(lines, Duration.ZERO, Duration.INFINITE, OsNetStamps.ProbeClock(Duration.ZERO))
        }
    }

    private fun path(address: InetAddress): String {
        val b = address.address

        fun hex(
            from: Int,
            count: Int,
        ) = (from until from + count).fold(0UL) { acc, i -> (acc shl 8) or (b[i].toULong() and 0xffUL) }.toString(16)
        return if (b.size == 4) "4:4000:0:${hex(0, 4)}" else "6:4000:${hex(0, 8)}:${hex(8, 8)}"
    }
}
