package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.testkit.trace.TraceSink as NeutralTraceSink

// `as NeutralTraceSink`: the bare `TraceSink` in this package is the deprecated compat typealias
// (DeprecatedTraceAliases.kt); aliasing lets this class reference the real neutral type clash-free.

/**
 * Consumer opt-in for capturing a QUIC connection's deterministic-replay trace — the public door to
 * the `QuicTraceRecorder` (RFC_DETERMINISTIC_SIMULATION.md §5, v1 grammar). Set
 * [com.ditchoom.socket.quic.QuicOptions.trace] to enable capture on a connection or server; the
 * production default is `null`, which is byte-identical to the pre-capture path (zero cost).
 *
 * When set, the QUIC engine wraps the connection's `UdpChannel`s in the recording decorator
 * (DGRAM_OUT / DGRAM_IN), mirrors state / path / close-error transitions, and polls path-stats on
 * the driver's timer wake — all encoded onto the sink [sinkFor] mints for that connection.
 *
 * **One sink = one connection.** The v1 trace grammar carries no connection identifier and each
 * connection records against its own clock origin, so a single sink is meant to receive exactly one
 * connection's lines for a clean, replayable trace. [sinkFor] is therefore a **factory**, invoked
 * once per captured connection: return a *fresh* sink each call (e.g. a new file, a new buffer) and
 * concurrent connections stay isolated and independently replayable. The convenience constructor
 * that takes a single [com.ditchoom.socket.testkit.trace.TraceSink] is the opposite choice on purpose — it hands the *same* sink to
 * every connection (log-sink semantics), fine for one connection or for aggregate diagnostics but
 * not for per-connection replay.
 *
 * A server [bind][com.ditchoom.socket.quic.QuicEngine.bind] invokes [sinkFor] **once per accepted
 * connection**, so each accepted connection records onto its own sink — per-connection, independently
 * replayable server traces (this is what makes deterministic *server* replay possible). Return a
 * fresh sink per call, exactly as for the client; a [sinkFor] that hands back one shared sink (the
 * single-[com.ditchoom.socket.testkit.trace.TraceSink] convenience constructor) instead interleaves every accepted connection onto it —
 * aggregate diagnostics, not per-connection replay.
 *
 * @property sinkFor mints the [com.ditchoom.socket.testkit.trace.TraceSink] for a captured connection. Called once per connection — the
 *   client `connect`, or each accepted connection on a server `bind` — so return a fresh sink per call
 *   for independent, replayable traces. The consumer
 *   owns IO (append to a file, ship over the network, buffer in memory) so capture stays platform-free.
 *   Each returned sink may be called from several coroutines concurrently — treat it like a log sink.
 * @property networkMonitor optional connectivity tap. When supplied, a **client** connection also
 *   collects the monitor's `availability` + `networkId` flows into the trace (NET_AVAIL / NET_ID),
 *   so captured traces carry connectivity state — the airplane-mode toggle / Wi-Fi↔cellular handoff
 *   that drives the transport layer's reconnection — not just QUIC-level traffic. Ignored on a
 *   server [bind][com.ditchoom.socket.quic.QuicEngine.bind] (a server has no local client network
 *   path to observe). Liveness (LIVENESS) is captured separately via
 *   [com.ditchoom.socket.quic.trace] `QuicTraceRecorder.wrap`, wired at the transport seam that
 *   drives probes ([com.ditchoom.socket.transport.Liveness]) — that seam lives above the engine.
 */
class QuicTraceCapture(
    val sinkFor: () -> NeutralTraceSink,
    val networkMonitor: NetworkMonitor? = null,
) {
    /**
     * Convenience for the single-connection case: every captured connection records onto the one
     * [sink] supplied here (log-sink semantics). Correct for a single connection or for aggregate
     * server diagnostics; for per-connection replay across concurrent connections use the primary
     * [sinkFor] factory constructor and return a fresh sink each call.
     */
    constructor(sink: NeutralTraceSink, networkMonitor: NetworkMonitor? = null) : this({ sink }, networkMonitor)
}
