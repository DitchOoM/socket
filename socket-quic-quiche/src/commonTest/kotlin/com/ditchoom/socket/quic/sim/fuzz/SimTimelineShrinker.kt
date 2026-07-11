package com.ditchoom.socket.quic.sim.fuzz

import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.sim.SimEvent
import com.ditchoom.socket.quic.sim.SimFixture
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * The W5 shrinker (RFC_DETERMINISTIC_SIMULATION.md §6 item 3): minimize a failing timeline into
 * the smallest event subset that still violates an invariant, entirely under virtual time (every
 * candidate re-runs the full invariant harness in milliseconds of wall-clock).
 *
 * Strategy (delta-debugging lite): remove contiguous chunks — first halves, then ever-smaller
 * chunks down to single events — keeping any removal that still fails, until a full pass at chunk
 * size 1 removes nothing more. The driver configuration ([FuzzCase.keepAliveInterval]) and the
 * fixture horizon are pinned — shrinking only the *events* keeps the timer trajectory (keepalive /
 * idle deadlines) identical, so the minimized timeline fails for the same reason, not a new one.
 * Any-violation semantics: a candidate counts as failing if it violates *any* invariant (standard
 * shrinker trade-off; the violation actually minimized for is re-computed on the final subset).
 *
 * Returns the minimal case, its violations, and [renderAsSimFixtureDsl] output — a ready-to-paste
 * `simFixture("shrunk-<seed>") { ... }` block for committing as a regression fixture.
 */
internal suspend fun TestScope.shrinkFuzzCase(original: FuzzCase): ShrunkCase {
    var events = original.fixture.events.sortedBy { it.at }
    var chunk = maxOf(1, events.size / 2)
    while (true) {
        var removedAny = false
        while (chunk >= 1) {
            var i = 0
            while (i < events.size) {
                val candidate = events.subList(0, i) + events.subList(minOf(i + chunk, events.size), events.size)
                if (checkFuzzInvariants(original.withEvents(candidate)).failed) {
                    events = candidate
                    removedAny = true
                    // Same index now holds the next chunk — do not advance.
                } else {
                    i += chunk
                }
            }
            chunk /= 2
        }
        if (!removedAny || events.isEmpty()) break
        chunk = 1 // one more single-event pass — earlier removals can unlock new ones
    }
    val minimal = original.withEvents(events)
    val verdict = checkFuzzInvariants(minimal)
    return ShrunkCase(minimal, verdict.violations, renderAsSimFixtureDsl(minimal))
}

internal class ShrunkCase(
    val case: FuzzCase,
    val violations: List<String>,
    val fixtureDsl: String,
)

private fun FuzzCase.withEvents(events: List<SimEvent>): FuzzCase =
    FuzzCase(seed, SimFixture(fixture.name, events, fixture.duration), keepAliveInterval)

/**
 * Render a case as a compilable `simFixture` DSL block (plus the driver config as a comment) — the
 * committed-fixture form fuzz findings and field captures share (RFC §6: one corpus).
 */
internal fun renderAsSimFixtureDsl(case: FuzzCase): String =
    buildString {
        appendLine("// seed=${case.seed}  keepAliveInterval=${case.keepAliveInterval?.let { durationLiteral(it) } ?: "null"}")
        appendLine("val shrunk${case.seed} =")
        appendLine("    simFixture(\"shrunk-${case.seed}\") {")
        for (event in case.fixture.events.sortedBy { it.at }) {
            appendLine("        ${renderEvent(event)}")
        }
        appendLine("        runFor(${durationLiteral(case.fixture.duration)})")
        append("    }")
    }

private fun renderEvent(event: SimEvent): String {
    val at = "at(${durationLiteral(event.at)})"
    return when (event) {
        is SimEvent.DatagramIn -> "$at datagramIn \"${event.payloadHex}\""
        is SimEvent.SendError -> "$at sendError SimError(\"${event.error.message}\")"
        is SimEvent.RecvError -> "$at recvError SimError(\"${event.error.message}\")"
        is SimEvent.Availability -> "$at availability NetworkAvailability.${availabilityName(event.value)}"
        is SimEvent.Network -> "$at network ${networkIdLiteral(event.id)}"
        is SimEvent.Liveness -> "$at liveness Liveness.Result.${event.result.name}"
    }
}

private fun availabilityName(value: NetworkAvailability): String = value.name

private fun networkIdLiteral(id: NetworkId): String =
    when (id) {
        is NetworkId.Unidentified -> "NetworkId.Unidentified"
        is NetworkId.KindOnly -> "NetworkId.KindOnly(NetworkKind.${id.kind.kindName()})"
        is NetworkId.Link -> "NetworkId.Link(NetworkKind.${id.kind.kindName()}, ${id.handle}L)"
    }

private fun com.ditchoom.socket.transport.NetworkKind.kindName(): String =
    when (this) {
        is com.ditchoom.socket.transport.NetworkKind.Wifi -> "Wifi"
        is com.ditchoom.socket.transport.NetworkKind.Cellular -> "Cellular"
        is com.ditchoom.socket.transport.NetworkKind.Ethernet -> "Ethernet"
        is com.ditchoom.socket.transport.NetworkKind.Vpn -> "Vpn(...)"
        is com.ditchoom.socket.transport.NetworkKind.Other -> "Other(...)"
    }

/** Millisecond-exact Kotlin literal — every generated timestamp is whole milliseconds. */
private fun durationLiteral(d: Duration): String = "${d.inWholeMilliseconds}.milliseconds"
