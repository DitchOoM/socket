package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.BlockReason
import com.ditchoom.socket.InternetAccess
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.sim.SimError
import com.ditchoom.socket.quic.sim.SimEvent
import com.ditchoom.socket.quic.sim.SimFixture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlin.time.Duration
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * W3 fixture codegen (RFC_DETERMINISTIC_SIMULATION.md §3.2 "fixture codegen" + §5): converts the
 * **input-event** subset of a recorded trace ([TraceEvent.isInput] — DGRAM_IN, ERROR, NET, NET_CAP,
 * LIVENESS) into
 *
 *  1. an in-memory [SimFixture] ([toSimFixture]) replayable through the W2 `SimTimeline` engine, and
 *  2. a generated `.kt` file ([generateKotlin]) using the committed `simFixture` DSL — the
 *     embedded-hex portability pattern: the fixture compiles into every platform's test binary
 *     with zero runtime file IO, exactly like the H3/QPACK fuzz corpora.
 *
 * Observations (DGRAM_OUT/STATE/PATH_STATE/STATS) are deliberately dropped: they are the golden
 * trajectory replay asserts against, never inputs (RFC §2). Recorded ERRORs map to
 * [SimEvent.RecvError] — at the driver seam every non-cancellation channel fault takes the same
 * type-agnostic path, so receive-side injection reproduces the driver-visible behavior of both
 * directions (Tier A: structural, not byte-exact — see RFC §4).
 */
internal object TraceToFixture {
    /**
     * Map the replayable input events of [events] onto the W2 [SimEvent] model, in order.
     *
     * `NET_CAP` is an input but not a **timed** one: a monitor's [com.ditchoom.socket.MonitorCapability]
     * is constant for its lifetime, so it configures the replay's `SimNetworkMonitor` rather than firing
     * at an instant. It is dropped here and read separately by whoever builds the monitor.
     */
    fun toSimEvents(events: List<TraceEvent>): List<SimEvent> =
        events.filter { it.isInput && it !is TraceEvent.NetCapability }.map { event ->
            val at = event.at
            when (event) {
                is TraceEvent.DgramIn -> SimEvent.DatagramIn(at, event.payloadHex)
                is TraceEvent.Error -> SimEvent.RecvError(at, SimError("${event.type}: ${event.message}"))
                is TraceEvent.Net -> SimEvent.Net(at, event.state)
                is TraceEvent.Liveness -> SimEvent.Liveness(at, event.result)
                else -> error("not a timed input event: $event")
            }
        }

    /** Build an in-memory [SimFixture] from a recorded trace — the replay-smoke entry point. */
    fun toSimFixture(
        name: String,
        events: List<TraceEvent>,
        runFor: Duration = Duration.ZERO,
    ): SimFixture {
        val sim = toSimEvents(events)
        val lastAt = sim.maxOfOrNull { it.at } ?: Duration.ZERO
        return SimFixture(name, sim, maxOf(runFor, lastAt))
    }

    /**
     * Emit a committed-fixture `.kt` file: `internal val [valName]: SimFixture = simFixture(...)`.
     * The output belongs in `socket-quic-quiche/src/commonTest/.../sim/fixtures/` next to the
     * hand-written W2 goldens; imports are emitted only for the constructs the fixture uses.
     */
    fun generateKotlin(
        fixtureName: String,
        valName: String,
        events: List<TraceEvent>,
        runFor: Duration = Duration.ZERO,
        packageName: String = "com.ditchoom.socket.quic.sim.fixtures",
    ): String {
        val sim = toSimEvents(events)
        val imports = sortedSetOf<String>()
        imports += "com.ditchoom.socket.quic.sim.SimFixture"
        imports += "com.ditchoom.socket.quic.sim.simFixture"
        imports += "kotlin.time.Duration.Companion.nanoseconds"
        val body = StringBuilder()
        for (event in sim) {
            val at = "${event.at.inWholeNanoseconds}.nanoseconds"
            when (event) {
                is SimEvent.DatagramIn -> body.appendLine("        at($at) datagramIn \"${event.payloadHex}\"")
                is SimEvent.RecvError -> {
                    imports += "com.ditchoom.socket.quic.sim.SimError"
                    body.appendLine("        at($at) recvError SimError(${literal(event.error.message)})")
                }
                is SimEvent.SendError -> {
                    imports += "com.ditchoom.socket.quic.sim.SimError"
                    body.appendLine("        at($at) sendError SimError(${literal(event.error.message)})")
                }
                is SimEvent.Net -> {
                    imports += "com.ditchoom.socket.NetworkState"
                    // Exhaustive over the rungs rather than `as?` + a null check: only the rungs that
                    // carry a link need the NetworkId imports, and only Routable needs InternetAccess.
                    when (val state = event.state) {
                        NetworkState.Unknown, NetworkState.Offline -> Unit
                        is NetworkState.LinkLocal -> imports += importsFor(state.id)
                        is NetworkState.Routable -> {
                            imports += importsFor(state.id)
                            imports += "com.ditchoom.socket.InternetAccess"
                            if (state.internet is InternetAccess.Observed.Blocked) {
                                imports += "com.ditchoom.socket.BlockReason"
                            }
                        }
                    }
                    body.appendLine("        at($at) net ${render(event.state)}")
                }
                is SimEvent.Liveness -> {
                    imports += "com.ditchoom.socket.transport.Liveness"
                    body.appendLine("        at($at) liveness ${render(event.result)}")
                }
            }
        }
        val totalRunFor = maxOf(runFor, sim.maxOfOrNull { it.at } ?: Duration.ZERO)
        if (totalRunFor > Duration.ZERO) {
            body.appendLine("        runFor(${totalRunFor.inWholeNanoseconds}.nanoseconds)")
        }
        return buildString {
            appendLine("// GENERATED by TraceToFixture from recorded trace \"$fixtureName\" — do not edit.")
            appendLine("// Input-event subset of the capture (RFC_DETERMINISTIC_SIMULATION.md §2); replay via SimTimeline.")
            appendLine("package $packageName")
            appendLine()
            imports.forEach { appendLine("import $it") }
            appendLine()
            appendLine("internal val $valName: SimFixture =")
            appendLine("    simFixture(\"$fixtureName\") {")
            append(body)
            appendLine("    }")
        }
    }

    /** The imports a rendered [NetworkId] literal needs — [NetworkKind] only when there is a kind to name. */
    private fun importsFor(id: NetworkId): Set<String> =
        buildSet {
            add("com.ditchoom.socket.transport.NetworkId")
            if (id !is NetworkId.Unidentified) add("com.ditchoom.socket.transport.NetworkKind")
        }

    private fun render(state: NetworkState): String =
        when (state) {
            NetworkState.Unknown -> "NetworkState.Unknown"
            NetworkState.Offline -> "NetworkState.Offline"
            is NetworkState.LinkLocal -> "NetworkState.LinkLocal(${render(state.id)})"
            is NetworkState.Routable -> "NetworkState.Routable(${render(state.id)}, ${render(state.internet)})"
        }

    private fun render(access: InternetAccess): String =
        when (access) {
            InternetAccess.Unobserved -> "InternetAccess.Unobserved"
            InternetAccess.Observed.Confirmed -> "InternetAccess.Observed.Confirmed"
            InternetAccess.Observed.Pending -> "InternetAccess.Observed.Pending"
            InternetAccess.Observed.Limited -> "InternetAccess.Observed.Limited"
            is InternetAccess.Observed.Blocked -> "InternetAccess.Observed.Blocked(${render(access.reason)})"
        }

    private fun render(reason: BlockReason): String =
        when (reason) {
            BlockReason.CaptivePortal -> "BlockReason.CaptivePortal"
            BlockReason.Suspended -> "BlockReason.Suspended"
        }

    private fun render(result: TransportLiveness.Result): String = "Liveness.Result.${result.name}"

    private fun render(id: NetworkId): String =
        when (id) {
            is NetworkId.Unidentified -> "NetworkId.Unidentified"
            is NetworkId.KindOnly -> "NetworkId.KindOnly(${render(id.kind)})"
            is NetworkId.Link -> "NetworkId.Link(${render(id.kind)}, ${id.handle}L)"
        }

    private fun render(kind: NetworkKind): String =
        when (kind) {
            NetworkKind.Wifi -> "NetworkKind.Wifi"
            NetworkKind.Cellular -> "NetworkKind.Cellular"
            NetworkKind.Ethernet -> "NetworkKind.Ethernet"
            is NetworkKind.Vpn ->
                if (kind.transports.isEmpty()) {
                    "NetworkKind.Vpn()"
                } else {
                    "NetworkKind.Vpn(setOf(${kind.transports.joinToString(", ") { render(it) }}))"
                }
            is NetworkKind.Other -> "NetworkKind.Other(${literal(kind.raw)})"
        }

    private fun literal(s: String): String =
        buildString(s.length + 2) {
            append('"')
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> append("\\$")
                    else -> append(c)
                }
            }
            append('"')
        }
}
