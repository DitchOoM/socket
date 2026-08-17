package com.ditchoom.socket.quic

import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.QuicTraceRecorder
import kotlinx.coroutines.CoroutineScope

/*
 * Translates the public capture opt-in (QuicOptions.trace) into the internal driver seam
 * (QuicheDriverTuning.recorder) — the one hop the three QuicheEngine actuals share so a consumer's
 * QuicTraceCapture reaches every QuicheDriver built for a connection or server. The internal
 * QuicheDriverTuning seam is deliberately left untouched (tests keep injecting their own tuning
 * directly); these helpers only bridge the public surface onto it.
 */

/**
 * Build the opt-in [QuicTraceRecorder] for [quicOptions], or `null` when capture is off. The
 * recorder defaults its clock to [RealDriverClock] — the same singleton [QuicheDriverTuning.clock]
 * defaults to — so trace timestamps and driver timers share one clock (RFC §5 "one clock").
 */
internal fun traceRecorderFor(quicOptions: QuicOptions): QuicTraceRecorder? = quicOptions.trace?.let { QuicTraceRecorder(it.sinkFor()) }

/**
 * Client-side connectivity tap (RFC §5.1): when the capture opt-in asked for network observations,
 * record [monitor]'s capability once (NET_CAP), then collect its single `state` flow into [recorder] for
 * the connection's lifetime (NET — one collector, so the recorded stream is time-ordered rather than
 * scheduling-interleaved). [scope] is the connection itself (every `QuicConnection` is a
 * [CoroutineScope]), so the collector is cancelled when the connection closes.
 *
 * [monitor] is the connection's **own** resolved monitor, passed in rather than read from a second field
 * on [QuicTraceCapture]. That second field is what this replaces: a caller could hand `QuicOptions` two
 * different monitors — one for auto-migration, one for the trace — and get a capture whose NET lines
 * indexed an observation stream nothing else in the connection had seen.
 *
 * No-op when capture is off or the capture did not ask for observations. The server bind path never
 * calls this at all (a server has no local client network path to observe).
 */
internal fun wireClientConnectivityTap(
    quicOptions: QuicOptions,
    recorder: QuicTraceRecorder?,
    scope: CoroutineScope,
    monitor: NetworkMonitor,
) {
    if (quicOptions.trace?.recordNetworkObservations != true) return
    recorder?.observe(monitor, scope)
}
