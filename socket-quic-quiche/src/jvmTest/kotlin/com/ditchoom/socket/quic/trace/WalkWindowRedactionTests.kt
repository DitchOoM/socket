package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This repository is public: nothing committed from a walk (the windows in `resources/walk-traces/` and the fixtures
 * built from them) may name a real address, and one redaction table handles every place an address appears.
 */
class WalkWindowRedactionTests {
    private class Walk(
        val windowResource: String,
        val fixtureFile: String,
    )

    private val walks =
        listOf(
            Walk(
                "walk-traces/20260919T153909Z-walk-0912-0915-conn-0007-from-13066795292.trace",
                "Walk20260912Conn7DeadLinkHandoff.kt",
            ),
            Walk(
                "walk-traces/20260911T155035Z-walk-2026-09-10-conn-0001-from-1572876724625.trace",
                "Walk20260910Conn1DeadLinkHandoff.kt",
            ),
            Walk(
                "walk-traces/20260924T212145Z-walk-0920-leg3-conn-0001-from-25141684665792.trace",
                "Walk20260920Leg3Conn1DownlinkBlackout.kt",
            ),
            Walk(
                "walk-traces/20260924T220750Z-walk-0924-short-conn-v4-0001-from-1193636943191.trace",
                "Walk20260924V4Conn1CellularNotYetPassing.kt",
            ),
            Walk(
                "walk-traces/20260924T220750Z-walk-0924-short-conn-v4-0002-from-382604493031-to-396000000000.trace",
                "Walk20260924V4Conn2AbruptHandoff.kt",
            ),
            Walk(
                "walk-traces/20261101T193304Z-walk-1031-flight-conn-v4-0187-from-2661255539042-to-2680000000000.trace",
                "Walk20261031V4Conn187LandingHandoff.kt",
            ),
            Walk(
                "walk-traces/20261101T232429Z-walk-1031-flight-conn-v4-cellular-0165-from-67195738099.trace",
                "Walk20261031V4CellularConn165DataSuspended.kt",
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

    private fun path(address: InetAddress): String {
        val b = address.address

        fun hex(
            from: Int,
            count: Int,
        ) = (from until from + count).fold(0UL) { acc, i -> (acc shl 8) or (b[i].toULong() and 0xffUL) }.toString(16)
        return if (b.size == 4) "4:4000:0:${hex(0, 4)}" else "6:4000:${hex(0, 8)}:${hex(8, 8)}"
    }
}
