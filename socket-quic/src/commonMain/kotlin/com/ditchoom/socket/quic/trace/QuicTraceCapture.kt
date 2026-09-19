package com.ditchoom.socket.quic.trace

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
 * the driver's timer wake — all encoded onto the sink [captureFor] mints for that connection.
 *
 * **One capture = one connection.** The v1 trace grammar carries no connection identifier and each
 * connection records against its own clock origin, so a single sink is meant to receive exactly one
 * connection's lines for a clean, replayable trace. [captureFor] is therefore a **factory**, invoked
 * once per captured connection: return a *fresh* [QuicConnectionCapture] each call (a new file, a new
 * buffer) and concurrent connections stay isolated and independently replayable. It mints the
 * connection's [QlogTarget] in the same call, so a consumer that also wants quiche's own frame-level
 * record names the two records together — `conn-0007.trace` beside `conn-0007.sqlog` — from one
 * sequence number it owns. The convenience factory that takes a single
 * [com.ditchoom.socket.testkit.trace.TraceSink] is the opposite choice on purpose — it hands the
 * *same* sink to every connection (log-sink semantics), fine for one connection or for aggregate
 * diagnostics but not for per-connection replay.
 *
 * A server [bind][com.ditchoom.socket.quic.QuicEngine.bind] invokes [captureFor] **once per accepted
 * connection**, so each accepted connection records onto its own sink — per-connection, independently
 * replayable server traces (this is what makes deterministic *server* replay possible). Return a
 * fresh capture per call, exactly as for the client; a factory that hands back one shared sink (the
 * single-[com.ditchoom.socket.testkit.trace.TraceSink] convenience) instead interleaves every
 * accepted connection onto it — aggregate diagnostics, not per-connection replay.
 *
 * @property captureFor mints the [QuicConnectionCapture] for a captured connection. Called once per
 *   connection — the client `connect`, or each accepted connection on a server `bind` — so return a
 *   fresh one per call for independent, replayable traces. The consumer owns the trace's IO (append
 *   to a file, ship over the network, buffer in memory) so capture stays platform-free; the qlog's IO
 *   is quiche's, which is why that half is a path. Each returned sink may be called from several
 *   coroutines concurrently — treat it like a log sink.
 * @property recordNetworkObservations record the connection's network observations (NET / NET_CAP) into
 *   the trace alongside its QUIC traffic. The monitor recorded is **the connection's own** — the one
 *   [com.ditchoom.socket.quic.QuicOptions.networkMonitor] resolves — so a captured trace, an automatic
 *   migration, and [com.ditchoom.socket.quic.QuicConnection.networkAtClose] all describe the same
 *   observation stream. A second, independent monitor field here would let a caller hand two fields
 *   two different monitors and get a trace that indexed a stream nothing else had seen.
 *
 *   A plain `Boolean` because that is the whole truth here: *which* monitor is a separate, already-typed
 *   decision ([com.ditchoom.socket.quic.NetworkMonitorSource]), and this only says whether to write it
 *   down. Ignored on a server [bind][com.ditchoom.socket.quic.QuicEngine.bind] — a server has no local
 *   client network path to observe. Liveness (LIVENESS) is captured separately via
 *   `QuicTraceRecorder.wrap`, wired at the transport seam that drives probes
 *   ([com.ditchoom.socket.transport.Liveness]) — that seam lives above the engine.
 */
class QuicTraceCapture(
    val captureFor: () -> QuicConnectionCapture,
    val recordNetworkObservations: Boolean = false,
) {
    /**
     * Convenience for the single-connection case: every captured connection records onto the one
     * [sink] supplied here (log-sink semantics). Correct for a single connection or for aggregate
     * server diagnostics; for per-connection replay across concurrent connections use the
     * [captureFor] factory and return a fresh capture each call.
     */
    constructor(sink: NeutralTraceSink, recordNetworkObservations: Boolean = false) :
        this({ QuicConnectionCapture(sink) }, recordNetworkObservations)

    companion object {
        /**
         * The trace-only factory form: [sinkFor] mints a fresh sink per connection and no qlog is
         * named ([QlogTarget.Off]). A companion factory rather than a third constructor because a
         * `() -> TraceSink` and a `() -> QuicConnectionCapture` parameter erase to the same JVM
         * signature.
         */
        operator fun invoke(
            sinkFor: () -> NeutralTraceSink,
            recordNetworkObservations: Boolean = false,
        ): QuicTraceCapture = QuicTraceCapture({ QuicConnectionCapture(sinkFor()) }, recordNetworkObservations)
    }
}
