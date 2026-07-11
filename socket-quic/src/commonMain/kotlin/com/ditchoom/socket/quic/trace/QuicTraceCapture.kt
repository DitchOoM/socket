package com.ditchoom.socket.quic.trace

import com.ditchoom.socket.NetworkMonitor

/**
 * Consumer opt-in for capturing a QUIC connection's deterministic-replay trace — the public door to
 * the `QuicTraceRecorder` (RFC_DETERMINISTIC_SIMULATION.md §5, v1 grammar). Set
 * [com.ditchoom.socket.quic.QuicOptions.trace] to enable capture on a connection or server; the
 * production default is `null`, which is byte-identical to the pre-capture path (zero cost).
 *
 * When set, the QUIC engine wraps the connection's `UdpChannel`s in the recording decorator
 * (DGRAM_OUT / DGRAM_IN), mirrors state / path / close-error transitions, and polls path-stats on
 * the driver's timer wake — all encoded onto [sink].
 *
 * **One capture per connection.** The v1 trace grammar carries no connection identifier and each
 * connection records against its own clock origin, so a [sink] is meant to receive exactly one
 * connection's lines for a clean, replayable trace. Reusing one [QuicTraceCapture] across concurrent
 * connections interleaves them onto the shared [sink] (log-sink semantics) — give each connection its
 * own capture if you intend to replay. A server [bind][com.ditchoom.socket.quic.QuicEngine.bind]
 * likewise records **all** accepted connections onto the one [sink] (useful for aggregate diagnostics,
 * not per-connection replay).
 *
 * @property sink where each encoded `v1` trace line goes. The consumer owns IO (append to a file,
 *   ship over the network, buffer in memory) so capture stays platform-free. [sink] may be called
 *   from several coroutines concurrently — treat it like a log sink.
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
    val sink: TraceSink,
    val networkMonitor: NetworkMonitor? = null,
)
