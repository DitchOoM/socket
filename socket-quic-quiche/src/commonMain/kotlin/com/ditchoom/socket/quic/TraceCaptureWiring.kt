package com.ditchoom.socket.quic

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
internal fun traceRecorderFor(quicOptions: QuicOptions): QuicTraceRecorder? = quicOptions.trace?.let { QuicTraceRecorder(it.sink) }

/**
 * Client-side connectivity tap (RFC §5.1): when the capture opt-in supplied a
 * [com.ditchoom.socket.NetworkMonitor], collect its `availability` + `networkId` flows into
 * [recorder] for the connection's lifetime (NET_AVAIL / NET_ID). [scope] is the connection itself
 * (every `QuicConnection` is a [CoroutineScope]), so the collectors are cancelled when the
 * connection closes. No-op when capture is off or no monitor was supplied — the server bind path
 * never calls this (a server has no local client network path to observe).
 */
internal fun wireClientConnectivityTap(
    quicOptions: QuicOptions,
    recorder: QuicTraceRecorder?,
    scope: CoroutineScope,
) {
    val monitor = quicOptions.trace?.networkMonitor ?: return
    recorder?.observe(monitor, scope)
}
