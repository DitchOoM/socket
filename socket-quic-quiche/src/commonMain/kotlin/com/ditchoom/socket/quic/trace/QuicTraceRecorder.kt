package com.ditchoom.socket.quic.trace

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.quic.DriverClock
import com.ditchoom.socket.quic.PathInfo
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.RealDriverClock
import com.ditchoom.socket.quic.UdpChannel
import com.ditchoom.socket.transport.NetworkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import com.ditchoom.socket.transport.Liveness as TransportLiveness

/**
 * Opt-in capture tap for a quiche-backed QUIC connection — the W3 `TraceRecorder` of
 * RFC_DETERMINISTIC_SIMULATION.md §5/§5.1. Enable by setting `QuicheDriverTuning.recorder`; when
 * set, the driver wraps its `UdpChannel`s in a recording decorator ([wrap]), mirrors its
 * state/pathState transitions into the trace, and polls quiche path-stats on its existing timer
 * wake. Connectivity ([observe]) and liveness ([wrap]) taps are caller-wired, since those seams
 * live above the driver.
 *
 * All timestamps come from ONE clock — the [DriverClock] passed here, which must be the same
 * instance the driver runs on ([com.ditchoom.socket.quic.QuicheDriverTuning.clock]) so trace
 * stamps and driver timers can never skew (RFC §5 "one clock").
 *
 * ## Trace line grammar (version `v1`)
 *
 * One event per line; fields are single-space-delimited; payloads are lowercase hex (the
 * embedded-hex portability pattern — no runtime file IO needed to replay):
 *
 * ```
 * line       := "v1" SP t-nanos SP event
 * t-nanos    := decimal nanoseconds since the recorder's clock origin (monotonic)
 * event      := "DGRAM_OUT" SP len SP path SP hex        ; observation: datagram sent
 *             | "DGRAM_IN"  SP len SP path SP hex        ; input: datagram received
 *             | "STATE" SP name [SP detail...]           ; observation: QuicConnectionState
 *             | "PATH_STATE" SP phase SP host SP port    ; observation: PathInfo (host "-" if null)
 *             | "ERROR" SP type SP message...            ; input: typed error (class name + message)
 *             | "STATS" SP f1 .. f18                     ; observation: QuicPathStats snapshot, in
 *                                                        ;   declaration order (durations as nanos,
 *                                                        ;   active as 0/1)
 *             | "NET_AVAIL" SP (AVAILABLE|UNAVAILABLE|UNKNOWN)          ; input
 *             | "NET_ID" SP netid                                       ; input
 *             | "LIVENESS" SP (Alive|Dead|Unknown)                      ; input
 * path       := "-" | family ":" port ":" hi-hex ":" lo-hex             ; PathKey
 * netid      := "Unidentified" | "KindOnly:" kind | "Link:" kind ":" handle
 * kind       := "Wifi" | "Cellular" | "Ethernet"
 *             | "Vpn(" [kind ("," kind)*] ")" | "Other(" escaped-label ")"
 * ```
 *
 * `detail`/`message` are the line's tail (may contain spaces; newlines are flattened). The
 * `Other(...)` label is %-escaped so no `kind` ever contains a delimiter. [QuicTraceParser]
 * decodes lines back into [QuicTraceEvent]s; `parse(emit(e)) == e` holds for every event type.
 */
class QuicTraceRecorder(
    private val sink: TraceSink,
    clock: DriverClock = RealDriverClock,
) {
    private val origin: TimeMark = clock.markNow()

    private fun nowNanos(): Long = origin.elapsedNow().inWholeNanoseconds

    /** Encode and emit one pre-stamped [event] as a `v1` line. */
    fun record(event: QuicTraceEvent) {
        sink.emit(encodeTraceLine(event))
    }

    /** Record a `QuicConnectionState` transition (STATE). */
    fun connectionState(state: QuicConnectionState) {
        val detail =
            when (state) {
                is QuicConnectionState.Established -> state.negotiatedAlpn
                is QuicConnectionState.Closed -> state.error?.describe()
                else -> null
            }
        record(QuicTraceEvent.State(nowNanos(), state::class.simpleName ?: "Unknown", detail))
    }

    /** Record a `PathInfo` (migration) transition (PATH_STATE). */
    fun pathState(info: PathInfo) {
        record(QuicTraceEvent.PathState(nowNanos(), info.phase.name, info.localHost, info.localPort))
    }

    /** Record a typed exception (ERROR) — class name + message, never a bare string error. */
    fun error(error: Throwable) {
        record(QuicTraceEvent.Error(nowNanos(), error::class.simpleName ?: "Throwable", error.message ?: ""))
    }

    /** Record a typed QUIC close reason (ERROR) — the sealed class name + [QuicError.describe]. */
    fun closeError(error: QuicError) {
        record(QuicTraceEvent.Error(nowNanos(), error::class.simpleName ?: "QuicError", error.describe()))
    }

    /** Record a path-stats snapshot (STATS). */
    fun stats(stats: QuicPathStats) {
        record(QuicTraceEvent.Stats(nowNanos(), stats))
    }

    /** Record a `NetworkMonitor.availability` emission (NET_AVAIL). */
    fun networkAvailability(value: NetworkAvailability) {
        record(QuicTraceEvent.NetAvail(nowNanos(), value))
    }

    /** Record a `NetworkMonitor.networkId` emission (NET_ID). */
    fun networkId(id: NetworkId) {
        record(QuicTraceEvent.Net(nowNanos(), id))
    }

    /** Record a liveness probe outcome (LIVENESS). */
    fun livenessResult(result: TransportLiveness.Result) {
        record(QuicTraceEvent.Liveness(nowNanos(), result))
    }

    /**
     * Decorate a [UdpChannel] so every datagram through it is recorded (DGRAM_OUT / DGRAM_IN with
     * [path] as the PathKey, when known) and every non-cancellation IO failure is recorded typed
     * (ERROR) before rethrowing. The driver wraps its per-path channels here — the single
     * platform-neutral choke point of RFC §5.1 item 1.
     */
    fun wrap(
        channel: UdpChannel,
        path: PathKey? = null,
    ): UdpChannel = RecordingUdpChannel(channel, this, path)

    /** Decorate a [TransportLiveness] so every probe outcome is recorded (LIVENESS). */
    fun wrap(liveness: TransportLiveness): TransportLiveness =
        TransportLiveness {
            liveness.probe().also { livenessResult(it) }
        }

    /**
     * Collect [monitor]'s `availability` + `networkId` flows into the trace (NET_AVAIL / NET_ID),
     * including their current values, until [scope] is cancelled. Returns the collector [Job].
     */
    fun observe(
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            launch { monitor.availability.collect { networkAvailability(it) } }
            launch { monitor.networkId.collect { networkId(it) } }
        }

    internal fun datagram(
        out: Boolean,
        buffer: PlatformBuffer,
        len: Int,
        path: PathKey?,
    ) {
        val hex = hexOf(buffer, len)
        val t = nowNanos()
        record(
            if (out) {
                QuicTraceEvent.DgramOut(t, len, path, hex)
            } else {
                QuicTraceEvent.DgramIn(t, len, path, hex)
            },
        )
    }

    private companion object {
        private const val HEX = "0123456789abcdef"

        /** Absolute-indexed hex of [buffer]'s first [len] bytes — no ByteArray, no cursor movement. */
        private fun hexOf(
            buffer: PlatformBuffer,
            len: Int,
        ): String =
            buildString(len * 2) {
                for (i in 0 until len) {
                    val b = buffer[i].toInt() and 0xFF
                    append(HEX[b ushr 4])
                    append(HEX[b and 0x0F])
                }
            }
    }
}

/**
 * The recording [UdpChannel] decorator: transparent pass-through plus DGRAM_OUT on every
 * successful send, DGRAM_IN on every non-empty receive, and a typed ERROR on IO failure
 * (cancellation passes through unrecorded — it is lifecycle, not a network event).
 */
private class RecordingUdpChannel(
    private val delegate: UdpChannel,
    private val recorder: QuicTraceRecorder,
    private val path: PathKey?,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int {
        val received =
            try {
                delegate.receive(buffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recorder.error(e)
                throw e
            }
        if (received > 0) recorder.datagram(out = false, buffer = buffer, len = received, path = path)
        return received
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        dest: PathKey?,
    ) {
        try {
            delegate.send(buffer, len, dest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recorder.error(e)
            throw e
        }
        recorder.datagram(out = true, buffer = buffer, len = len, path = dest ?: path)
    }

    override fun close() = delegate.close()
}
