package com.ditchoom.socket.quic.trace

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.MonitorCapability
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.NetworkState
import com.ditchoom.socket.quic.DriverClock
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.RealDriverClock
import com.ditchoom.socket.quic.SendOutcome
import com.ditchoom.socket.quic.StreamEnd
import com.ditchoom.socket.quic.UdpChannel
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TracePathStats
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.udp.DatagramSendException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
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
 *             | "PATH_STATE" SP phase SP host SP port    ; observation: QuicPathState (host "-" / port
 *                                                        ;   0 when the state names no endpoint)
 *             | "ERROR" SP type SP message...            ; input: typed error (class name + message)
 *             | "STATS" SP f1 .. f18                     ; observation: QuicPathStats snapshot, in
 *                                                        ;   declaration order (durations as nanos,
 *                                                        ;   active as 0/1)
 *             | "NET" SP netstate                                       ; input: NetworkState rung
 *             | "NET_GAP" SP dropped                                    ; input: observations lost
 *                                                                       ;   before the NEXT "NET" line
 *             | "NET_CAP" SP mechanism SP resolution                    ; input: MonitorCapability, once
 *             | "LIVENESS" SP (Alive|Dead|Unknown)                      ; input
 * path       := "-" | family ":" port ":" hi-hex ":" lo-hex             ; PathKey
 * netstate   := "Unknown" | "Offline" | "LinkLocal" SP netid | "Routable" SP netid SP internet
 * internet   := "Unobserved" | "Confirmed" | "Pending" | "Limited" | "Blocked:CaptivePortal" | "Blocked:Suspended"
 * mechanism  := "PlatformSignalled" | "Static" | "Unknown" | "Polled(" nanos ")"
 * resolution := "RouteAndInternet" | "RouteOnly" | "LinkOnly" | "Asserted"
 * netid      := "Unidentified" | "KindOnly:" kind | "Link:" kind ":" handle
 * kind       := "Wifi" | "Cellular" | "Ethernet"
 *             | "Vpn(" [kind ("," kind)*] ")" | "Other(" escaped-label ")"
 * ```
 *
 * `detail`/`message` are the line's tail (may contain spaces; newlines are flattened). The
 * `Other(...)` label is %-escaped so no `kind` ever contains a delimiter. [TraceEvent.parse]
 * decodes lines back into [TraceEvent]s; `parse(e.toString()) == e` holds for every event type.
 */
class QuicTraceRecorder(
    private val sink: TraceSink,
    clock: DriverClock = RealDriverClock,
) {
    private val origin: TimeMark = clock.markNow()

    /** Offset from the recorder's single clock origin — a Duration, matching [TraceEvent.at]. */
    private fun now(): Duration = origin.elapsedNow()

    /** Emit one pre-stamped [event] to the sink. The sink owns serialization ([TraceEvent.toString]). */
    fun record(event: TraceEvent) {
        sink.emit(event)
    }

    /**
     * Record a `QuicConnectionState` transition (STATE). [TraceEvent.State.name] is the state's
     * **qualified** class name so an obfuscated Android-release trace retraces against `mapping.txt`.
     */
    fun connectionState(state: QuicConnectionState) {
        val detail =
            when (state) {
                is QuicConnectionState.Established -> state.negotiatedAlpn
                // Name the side and keep "unexplained" distinct from "graceful" — the trace is the
                // artifact someone reads after the fact, and collapsing those two is what made the
                // API-35 teardown undiagnosable.
                is QuicConnectionState.Closed ->
                    when (val r = state.reason) {
                        is QuicCloseReason.ByPeer -> "peer: ${r.error.describe()}"
                        is QuicCloseReason.ByLocal -> "local: ${r.error.describe()}"
                        QuicCloseReason.Graceful -> "graceful"
                        QuicCloseReason.Unspecified -> "unspecified"
                    }
                else -> null
            }
        record(TraceEvent.State(now(), state::class.qualifiedName ?: "Unknown", detail))
    }

    /**
     * Record a [QuicPathState] (migration) transition (PATH_STATE).
     *
     * The v1 wire tokens are frozen — `None`/`Probing`/`Validated`/`Migrated`/`Failed`, the names the
     * long-deleted `MigrationPhase` enum happened to have — and this is the boundary that translates
     * onto them, exactly as `PathKey.toTracePath()` translates the path types a few lines below. Keeping
     * the translation here is what lets `:socket-quic` reshape its path model without invalidating a
     * single recorded trace: `Original` still writes `None`, and a state that names no endpoint still
     * writes the `-`/`0` pair a `PathInfo` with a null host used to.
     */
    fun pathState(state: QuicPathState) {
        val (token, endpoint) =
            when (state) {
                QuicPathState.Original -> "None" to null
                is QuicPathState.Probing -> "Probing" to state.endpoint
                is QuicPathState.Validated -> "Validated" to state.endpoint
                is QuicPathState.Migrated -> "Migrated" to state.endpoint
                is QuicPathState.Failed -> "Failed" to null
            }
        // The `?.`/`?:` is the wire-format adapter, not a meaning-bearing nullable: v1 already spells
        // "names no endpoint" as `-`/`0`, and typing it away here would churn every golden fixture.
        record(TraceEvent.PathState(now(), token, endpoint?.host, endpoint?.port ?: 0))
    }

    /**
     * Record bytes the transport accepted that the application will never receive (STREAM_LOSS).
     *
     * Every one of these is a permanent hole: quiche has already advanced the stream's receive offset
     * and credited flow control, so the peer will not resend. Recorded even where releasing is the
     * *correct* action — see [StreamLossCause] — because "correct to release" and "the application did
     * not get these bytes" are different statements, and only the second explains a short stream.
     *
     * The sealed cause is translated onto its frozen v1 token here, at the wire boundary, exactly as
     * [pathState] does: the driver stays typed, and a recorded trace survives the model being reshaped.
     */
    fun streamLoss(
        streamId: Long,
        bytes: Int,
        cause: StreamLossCause,
    ) {
        val token =
            when (cause) {
                StreamLossCause.ReaderGone -> "ReaderGone"
                StreamLossCause.QueueClosed -> "QueueClosed"
                StreamLossCause.SalvageUnclaimed -> "SalvageUnclaimed"
            }
        record(TraceEvent.StreamLoss(now(), streamId, bytes, token))
    }

    /**
     * Record a stream's read side reaching its terminal verdict (STREAM_END).
     *
     * The counterpart to [streamLoss], and deliberately a separate event: that one records bytes the
     * application will never receive, this one records the stream being *finished*, which can happen
     * with no byte lost at all. `salvageCancelledRecv` latches the FIN on the branch where the salvaged
     * chunk queued successfully — nothing is dropped, so [streamLoss] stays silent — and every later
     * read is then answered `End` from the slot without quiche being asked again. A trace that could
     * only say "no bytes were dropped" therefore read as an exoneration of exactly the path under
     * suspicion in issue #393.
     *
     * Both sealed inputs are translated onto frozen v1 tokens here, at the wire boundary, as
     * [streamLoss] and [pathState] do. The parameter is [StreamEnd.Terminal], not [StreamEnd]: an
     * un-ended stream has no verdict to record, so that case is unrepresentable rather than no-oped.
     */
    fun streamEnd(
        streamId: Long,
        end: StreamEnd.Terminal,
        site: StreamEndSite,
    ) {
        val kind =
            when (end) {
                // `Reset-<code>` and not `Reset(<code>)`: v1 lines are space-delimited and read by shell
                // tooling as often as by the parser, so the token stays one word with no brackets.
                is StreamEnd.Reset -> "Reset-${end.applicationErrorCode.value}"
                StreamEnd.Fin -> "Fin"
            }
        val siteToken =
            when (site) {
                StreamEndSite.TeardownDrain -> "TeardownDrain"
                StreamEndSite.CancelledRecvSalvage -> "CancelledRecvSalvage"
                StreamEndSite.ReadDelivery -> "ReadDelivery"
            }
        record(TraceEvent.StreamEndLatched(now(), streamId, kind, siteToken))
    }

    /**
     * Record a typed exception (ERROR) — **qualified** class name + message, never a bare string
     * error. The FQN keeps the error type retraceable against R8's `mapping.txt`.
     */
    fun error(error: Throwable) {
        record(TraceEvent.Error(now(), error::class.qualifiedName ?: "Throwable", error.message ?: ""))
    }

    /** Record a typed QUIC close reason (ERROR) — the sealed class's **qualified** name + [QuicError.describe]. */
    fun closeError(error: QuicError) {
        record(TraceEvent.Error(now(), error::class.qualifiedName ?: "QuicError", error.describe()))
    }

    /** Record a path-stats snapshot (STATS), projecting [QuicPathStats] onto the neutral [TracePathStats]. */
    fun stats(stats: QuicPathStats) {
        record(TraceEvent.Stats(now(), stats.toTracePathStats()))
    }

    /** Record a `NetworkMonitor.state` emission (NET) — the whole state, identity included. */
    fun networkState(state: NetworkState) {
        record(TraceEvent.Net(now(), state))
    }

    /** Record the monitor's [MonitorCapability] (NET_CAP), once — it never changes. */
    fun networkCapability(capability: MonitorCapability) {
        record(TraceEvent.NetCapability(now(), capability))
    }

    /** Record a liveness probe outcome (LIVENESS). */
    fun livenessResult(result: TransportLiveness.Result) {
        record(TraceEvent.Liveness(now(), result))
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
     * Record [monitor]'s capability (NET_CAP), then collect its `state` flow into the trace (NET),
     * including the current value, until [scope] is cancelled. Returns the collector [Job].
     *
     * **One collector**, which is what makes the recorded stream monotonic: the two-flow version this
     * replaced launched a collector per flow, so two independently-stamped streams interleaved by
     * scheduling rather than by time (RFC_NETWORK_REACHABILITY §1.2).
     */
    fun observe(
        monitor: NetworkMonitor,
        scope: CoroutineScope,
    ): Job =
        scope.launch {
            networkCapability(monitor.capability)
            monitor.state.collect { networkState(it) }
        }

    internal fun datagram(
        out: Boolean,
        buffer: PlatformBuffer,
        len: Int,
        path: PathKey?,
    ) {
        val hex = hexOf(buffer, len)
        val t = now()
        val tracePath = path?.toTracePath()
        record(
            if (out) {
                TraceEvent.DgramOut(t, len, tracePath, hex)
            } else {
                TraceEvent.DgramIn(t, len, tracePath, hex)
            },
        )
    }

    // Project the quiche-side path/stats types onto the neutral trace model in `:socket-quic` at the
    // two choke points that carry them — so `PathKey`/`QuicPathStats` stay in this module untouched.
    private fun PathKey.toTracePath(): TracePath = TracePath(family, port, hi, lo)

    private fun QuicPathStats.toTracePathStats(): TracePathStats =
        TracePathStats(
            validationState = validationState,
            active = active,
            recv = recv,
            sent = sent,
            lost = lost,
            retrans = retrans,
            totalPtoCount = totalPtoCount,
            rtt = rtt,
            minRtt = minRtt,
            maxRtt = maxRtt,
            rttvar = rttvar,
            cwnd = cwnd,
            sentBytes = sentBytes,
            recvBytes = recvBytes,
            lostBytes = lostBytes,
            streamRetransBytes = streamRetransBytes,
            pmtu = pmtu,
            deliveryRate = deliveryRate,
        )

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
    ): SendOutcome {
        // A send now reports its failure instead of raising it, so the recorder branches on the
        // outcome rather than catching. DatagramSendException is constructed only on the failure
        // path, purely to give the recorder the Throwable its trace format takes — the structured
        // reason stays the typed DatagramSendError carried by the outcome.
        val outcome = delegate.send(buffer, len, dest)
        return when (outcome) {
            is SendOutcome.Sent -> {
                recorder.datagram(out = true, buffer = buffer, len = len, path = dest ?: path)
                outcome
            }
            is SendOutcome.Failed -> {
                recorder.error(DatagramSendException(outcome.error))
                outcome
            }
        }
    }

    override fun close() = delegate.close()
}
