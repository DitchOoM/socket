package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The two walk fixtures in `commonTest/sim/fixtures/` are the output of [TraceToFixture] over the
 * committed windows in `resources/walk-traces/` — byte for byte, or this fails. The windows are the
 * pulled traces from their last inbound datagram on (`awk '$2>=<from>'`, every line kept); the same
 * call over the full trace in `ios-probe/device/logs/` produces the same source, since
 * [TraceToFixture.window] drops everything before `from` itself.
 */
class WalkFixtureRegenerationTests {
    private class Walk(
        val windowResource: String,
        val fixtureName: String,
        val valName: String,
        val fromNanos: Long,
        val fixtureFile: String,
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
        )

    private fun regenerate(walk: Walk): String {
        val window = checkNotNull(javaClass.classLoader.getResource(walk.windowResource)) { "missing ${walk.windowResource}" }
        val events = TraceEvent.parseAll(window.readText().lines())
        val from = walk.fromNanos.nanoseconds
        val closedAt = events.filterIsInstance<TraceEvent.State>().last { it.name.endsWith("Closed") }.at
        return TraceToFixture.generateKotlin(
            walk.fixtureName,
            walk.valName,
            TraceToFixture.window(events, from),
            runFor = closedAt - from,
        )
    }

    @Test
    fun theCommittedWalkFixturesAreTraceToFixturesOutputOverTheCommittedWindows() {
        for (walk in walks) {
            val committed = File("src/commonTest/kotlin/com/ditchoom/socket/quic/sim/fixtures/${walk.fixtureFile}")
            assertTrue(committed.isFile, "run from the module directory: ${committed.absolutePath} not found")
            assertEquals(
                regenerate(walk),
                committed.readText(),
                "${walk.fixtureFile} is not TraceToFixture's output over ${walk.windowResource}",
            )
        }
    }
}
