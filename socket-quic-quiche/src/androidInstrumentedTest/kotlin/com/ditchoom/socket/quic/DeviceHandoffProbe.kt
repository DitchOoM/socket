package com.ditchoom.socket.quic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.NetworkMonitor
import com.ditchoom.socket.installAndroidApplicationContext
import com.ditchoom.socket.processDefault
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.echo.EchoFailure
import com.ditchoom.socket.testkit.echo.EchoLivenessTotals
import com.ditchoom.socket.testkit.echo.EchoLivenessVerdict
import com.ditchoom.socket.testkit.echo.EchoLoop
import com.ditchoom.socket.testkit.echo.EchoLoopEnd
import com.ditchoom.socket.testkit.echo.EchoLoopEvent
import com.ditchoom.socket.testkit.echo.EchoOutcome
import com.ditchoom.socket.testkit.echo.EchoRead
import com.ditchoom.socket.testkit.echo.EchoSession
import com.ditchoom.socket.testkit.echo.EchoStream
import com.ditchoom.socket.testkit.echo.EchoWrite
import com.ditchoom.socket.testkit.echo.RunLiveness
import com.ditchoom.socket.testkit.echo.SessionEnd
import com.ditchoom.socket.testkit.echo.SilenceWatchdog
import com.ditchoom.socket.testkit.echo.StreamReply
import com.ditchoom.socket.testkit.migration.PoolProbeHistory
import com.ditchoom.socket.testkit.migration.PoolRecoveryVerdict
import com.ditchoom.socket.testkit.migration.forConnection
import com.ditchoom.socket.testkit.migration.forRun
import com.ditchoom.socket.testkit.osnet.OsNetWatch
import com.ditchoom.socket.testkit.trace.TraceBudget
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.testkit.walk.BuildRevision
import com.ditchoom.socket.testkit.walk.DiskFree
import com.ditchoom.socket.testkit.walk.LaneWatch
import com.ditchoom.socket.testkit.walk.WalkLane
import com.ditchoom.socket.testkit.walk.WalkTargets
import com.ditchoom.socket.testkit.walk.WalkTargetsParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * SCRATCH — a hand-driven on-device probe, not part of the automated suite.
 *
 * Records what a **real** QUIC connection does across a **real** network handoff, which nothing in this
 * repository has ever measured. Every migration test on every platform migrates away from a *healthy*
 * path (`127.0.0.1` never dies), and the impairment suite drops packets in the network, where the local
 * send still succeeds — so the exact condition this campaign exists to fix has never been reproduced.
 *
 * Operator walks: apartment (Wi-Fi) → elevator (signal dies) → outside (cellular) → back in (Wi-Fi).
 *
 * Writes a newline-delimited log to the app's external files dir; `adb pull` it afterwards. It logs
 * rather than asserts on purpose: the recording *is* the deliverable, and a probe that threw on the
 * first read timeout would destroy the evidence at the moment it got interesting.
 *
 * Run (device may be unplugged once it starts — the log lands on the device, not over adb):
 * ```
 * adb -s <serial> shell am instrument -w \
 *   -e class com.ditchoom.socket.quic.DeviceHandoffProbe \
 *   -e probeHost 178.156.248.95,2a01:4ff:f4:eb1a::1 -e probePort 44433 -e probeMinutes 12 \
 *   com.ditchoom.socket.quic.quiche.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `probeHost` is a comma-separated list, one lane per host, all running at once: each lane keeps its
 * own connection to its own target for the whole walk, so every family is exercised on every network
 * the device crosses. Every line names its lane (`lane=v6 ECHO-OK …`), or `lane=run` for the run's
 * own. Lanes, and why a lane never falls back to another family: see [WalkTargets].
 *
 * What the OS said about the device's network — the radio, the links, their address families — is
 * recorded once for the whole run, under `lane=run`: see [OsNetWatch].
 */
@RunWith(AndroidJUnit4::class)
class DeviceHandoffProbe {
    private fun arg(
        name: String,
        fallback: String,
    ): String = InstrumentationRegistry.getArguments().getString(name) ?: fallback

    @Test
    fun walkAroundAndRecordTheHandoff() {
        // Hand-driven only, as the class KDoc says — but a KDoc does not exclude a @Test from
        // connectedAndroidTest. Without this gate the CI emulator ran the probe for its full
        // probeMinutes against an unreachable Tailscale address, silently (it logs, never asserts),
        // which stalled both emulator lanes at 69/153 until the 25m job budget killed them.
        // The documented invocation passes -e probeHost explicitly, so requiring it costs nothing.
        val port = arg("probePort", "14433").toInt()
        val targets =
            when (val parsed = WalkTargets.parse(arg("probeHost", ""), port)) {
                WalkTargetsParse.NoHost ->
                    throw AssumptionViolatedException(
                        "hand-driven probe — pass -e probeHost <ip>[,<ip>] to run it (see class KDoc)",
                    )
                is WalkTargetsParse.Parsed -> parsed.targets
            }
        val minutes = arg("probeMinutes", "12").toInt()
        // The read deadline is not an argument: each connection's [EchoSession] derives it from the
        // round trips it measures, the path's own probe timeout (#599). That keeps the #393 salvage
        // path armed by the path's real tail rather than by a guess about it.
        // Echo cadence, injectable for the same reason the read deadline is. At the 2s default the
        // connection is very nearly IDLE at the instant a handoff lands — and an idle connection has
        // nothing in flight to strand on the path it is leaving, which is precisely the condition #393
        // needed. A 2s probe therefore exercises the easy case and reports it as a pass. Drop this to
        // ~100ms and every migration happens with traffic actually crossing it.
        val echoIntervalMs = arg("probeEchoIntervalMs", "2000").toLong()

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val log = File(dir, "quic-handoff-probe.log")
        val started = System.currentTimeMillis()

        fun now() = (System.currentTimeMillis() - started).milliseconds

        fun write(line: String) {
            val t = System.currentTimeMillis() - started
            val rendered = "t=${t}ms $line"
            log.appendText(rendered + "\n")
            // logcat too, so a tethered run is watchable live: adb logcat -s QuicHandoffProbe
            android.util.Log.i("QuicHandoffProbe", rendered)
        }

        // A line of the whole run's. Every line names who it belongs to, so an analyzer that does not
        // know lanes reads nothing it recognises instead of two lanes merged into one.
        fun emit(line: String) = write("${WalkLane.RUN_TOKEN} $line")

        // A previous run is never deleted here: it moves under previous/<stamp>/ until pull.sh
        // collects it. Every walk this rig has lost was lost by a START.
        val kept = PreviousRun.rotate(dir, listOf(log.name, "traces", "qlog"))

        // The replay trace's budget follows this run's own duration, cadence and lanes (see
        // [TraceBudget]) and is shared by every lane; -e probeTraceBudgetMb overrides the ceiling for a
        // run that wants a different one.
        val derived = TraceBudget.forWalk(minutes, echoIntervalMs.milliseconds, lanes = targets.lanes.size)
        val budget = arg("probeTraceBudgetMb", "").let { if (it.isEmpty()) derived else derived.withMegabytes(it.toLong()) }

        // Frame-level evidence, off by default: quiche's own record, a run of .sqlog segments per
        // connection in the app's external files dir where `adb pull` can reach it, named by the same
        // stem as the connection's trace (conn-v6-0003.sqlog beside conn-v6-0003.trace). Each lane keeps
        // its own budget, derived like the trace's and together held to half of what the disk has left
        // once the trace's budget is set aside: a full disk would drop qlog writes silently, and this log
        // with them. Every drop is a line of the lane that made it. Off by default because it is an
        // instrument, not a feature.
        val diskFreeAtStart = DiskFree.ofReading(dir.usableSpace)
        val qlog =
            if (arg("probeQlog", "").isNotEmpty()) {
                val qlogDir = File(dir, "qlog").also { it.mkdirs() }
                val perLane = QlogBudget.forWalk(minutes, echoIntervalMs.milliseconds, lanes = 1)
                val laneBudget =
                    when (diskFreeAtStart) {
                        is DiskFree.Known -> perLane.fittedTo((diskFreeAtStart.bytes - budget.bytes) / 2 / targets.lanes.size)
                        DiskFree.Unknown -> perLane
                    }
                WalkQlog.Into(
                    qlogDir,
                    targets.lanes.associate { lane ->
                        val laneEmit = lane.log(::write)
                        lane.label to QlogDirectory(qlogDir.absolutePath, laneBudget) { event -> laneEmit(event.line) }
                    },
                )
            } else {
                WalkQlog.Off
            }

        log.writeText("")
        emit(kept.line)
        emit(
            "START device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ${targets.line} " +
                "minutes=$minutes echoIntervalMs=$echoIntervalMs qlog=$qlog ${BuildRevision.parse(PROBE_BUILD_STAMP).line} " +
                diskFreeAtStart.line,
        )
        emit(targets.lanesLine(echoIntervalMs.milliseconds))
        when (qlog) {
            WalkQlog.Off -> Unit
            is WalkQlog.Into ->
                targets.lanes.forEach { lane ->
                    val directory = qlog.byLane.getValue(lane.label)
                    lane.log(::write)(directory.budget.line)
                }
        }

        // The record of the walk: one file per connection, appended as it happens, replayable through
        // `TraceToFixture` without re-walking anything. Per connection rather than one file because
        // the v1 grammar carries no connection id and each connection stamps against its own clock
        // origin — a single shared sink interleaves every reconnect into something no fixture can be
        // built from.
        //
        // Affordable at this rig's cadence, which is the only reason it can be unconditional.
        emit(budget.line)
        val traceDir = File(dir, "traces")
        traceDir.mkdirs()
        val traceFiles =
            WalkTraceFiles(traceDir, qlog, targets.lanes, budget.bytes) { spent ->
                emit(
                    "TRACE-BUDGET-SPENT bytes=$spent — trace capture stopped; the walk continues but is no " +
                        "longer replayable past this point. Raise -e probeTraceBudgetMb for the next run.",
                )
            }

        val deadline = started + minutes * 60_000L

        // A coroutine `delay` does NOT wake the application processor from suspend, so both the echo
        // loop and the QUIC keepalive stall the moment the screen locks — i.e. exactly when this probe
        // is in a pocket recording the walk it exists to record. A Doze whitelist does not help: that
        // governs network policy, not CPU suspend. Acquired WITH a timeout so a probe that dies can
        // never pin the AP awake; that timeout is also why no finally block is needed.
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socket:quic-handoff-probe")
        wakeLock.acquire(minutes * 60_000L + 120_000L)
        emit("WAKELOCK acquired held=${wakeLock.isHeld} timeoutMs=${minutes * 60_000L + 120_000L}")

        // One lane per target, all at once (see [WalkTargets]). Everything a lane counts is its own —
        // attempts, sessions, migration ledgers, backoff, watchdog, stall ring — so one lane's trouble
        // cannot hide in the other's numbers, and nothing is shared between lanes but the log and the
        // trace budget.
        val lanes = targets.lanes.map { ProbeLane(it, ::write) }

        // Live status in the shade, one line per lane, so the walk can be driven by what each
        // connection actually did rather than by a stopwatch. See [ProbeStatus].
        val status = ProbeStatus(ctx, ::emit, targets.lanes)

        // What the OS itself says about this device's network — the radio, the links, their address
        // families — recorded once for the whole run under `lane=run`, because it is one fact about
        // the phone rather than one per lane (see [OsNetWatch]). Every lane's live connection trace
        // gets a copy, so an offline replay of any one connection carries the network it was on.
        // App Startup normally captures the Context before any app code runs, but an instrumented-test
        // process is not an app; without it the process default would be the POLLING monitor and the
        // OS ladder would be recorded on a 5 s cadence instead of on the platform's own callbacks.
        NetworkMonitor.installAndroidApplicationContext(ctx)
        val monitor = NetworkMonitor.processDefault()
        val osSource = AndroidOsNetworkSource(ctx)
        val osNet = OsNetWatch(osSource, ::now, ::emit, TraceSink { event -> lanes.forEach { it.trace.value.emit(event) } })
        // The radio can move while the connectivity callbacks stay silent — a SIM registering, data
        // roaming coming up — and that is a state the ladder alone cannot tell from airplane mode.
        // Where the platform will push it (API 31+), it is recorded when it happens.
        osSource.register { osNet.sample(monitor.state.value) }

        suspend fun runLane(probe: ProbeLane) {
            val lane = probe.lane
            val target = lane.target
            val laneEmit = probe.emit
            val options =
                QuicOptions(
                    alpnProtocols = listOf("test"),
                    verifyPeer = false,
                    trace =
                        QuicTraceCapture(
                            captureFor = {
                                val connection = traceFiles.next(lane)
                                // The network the device is already on, so a fixture cut from this
                                // connection starts from it rather than from whatever changed later.
                                osNet.seed(connection.sink)
                                probe.trace.value = connection.sink
                                // Tee: the lane's ring keeps the cross-connection tail its watchdog needs,
                                // the file keeps this connection's own replayable trace. The qlog rides
                                // along unchanged; its directory reports every segment, drop and refusal as
                                // a line of this lane.
                                connection.copy(
                                    sink =
                                        TraceSink { event ->
                                            probe.ring.emit(event)
                                            connection.sink.emit(event)
                                        },
                                )
                            },
                            // The connectivity stream is half of every migration question — which
                            // trigger fired, and whether the platform had even noticed the link yet.
                            recordNetworkObservations = true,
                        ),
                    // Long enough that a dead path is not immediately reaped, short enough that the
                    // walk shows a death rather than a hang. Keepalive keeps an idle connection honest.
                    idleTimeout = 30.seconds,
                    keepAliveInterval = 5.seconds,
                    migration = MigrationPolicy.Automatic,
                )
            var retryDelayMs = RECONNECT_MIN_MS
            while (System.currentTimeMillis() < deadline) {
                val attempt = probe.watch.nextAttempt()
                val attemptStarted = System.currentTimeMillis()
                laneEmit("CONNECT-ATTEMPT n=$attempt ${target.line}")
                if (attempt > 1) status.onEnded(lane, "reconnecting (attempt $attempt)")
                // Per CONNECTION, not per run: a reconnect negotiates a brand-new CID pool, so a pool
                // exhausted on the previous connection says nothing about this one.
                val ledger = MigrationLedger(laneEmit)
                // Per connection too: what is still owed when a connection ends is what failed on it,
                // and how long it went without an answered echo is its own verdict.
                val session = EchoSession(connectedAt = now())
                try {
                    // NOTE: this `timeout` bounds the ENTIRE scope block, not just the connect —
                    // measured, the first run of this probe tore the connection down every 15s with
                    // `TimeoutCancellationException: Timed out waiting for 15000 ms` while echoes were
                    // flowing fine. So it has to cover the whole walk, not the handshake.
                    withQuicConnection(target.host, target.port, options, timeout = (minutes + 2).minutes) {
                        laneEmit("CONNECTED session=${identity.session} wire=${identity.wire} alpn=$negotiatedAlpn")

                        val stream = openStream()
                        var lastWire = identity.wire
                        probe.watch.connected()

                        // A DEDICATED collector rather than a poll inside the echo loop. Phase 4 bounds
                        // an unanswered path probe at ~3s (RFC 9000 §8.2.4), so a whole Probing ->
                        // Failed -> Probing sequence can fall between two samples of a poll and go
                        // UNCOUNTED — which would make a working fix look broken, because the acceptance
                        // criterion here is "N handoffs produce N migration attempts". StateFlow still
                        // conflates, but now at collector speed.
                        launch {
                            pathState.collect {
                                laneEmit("PATH $it")
                                status.onPath(lane, it.toString())
                                ledger.onPath(it)
                            }
                        }

                        val loop =
                            EchoLoop(
                                stream = QuicEchoStream(stream),
                                session = session,
                                interval = echoIntervalMs.milliseconds,
                                clock = ::now,
                                emit = laneEmit,
                                // The typed reason, side included: "we sent a frame the peer rejected" and
                                // "the peer sent us one" are opposite bugs, and a device log is all we get
                                // from a real handoff (#437).
                                closedBy = ::connectionClosedBy,
                            ) { event ->
                                when (event) {
                                    // A wire CID that rotates while the session id holds is exactly what a
                                    // successful migration looks like.
                                    is EchoLoopEvent.Round ->
                                        if (identity.wire != lastWire) {
                                            laneEmit("WIRE-CID-ROTATED session=${identity.session} wire=${identity.wire}")
                                            lastWire = identity.wire
                                        }
                                    is EchoLoopEvent.Read -> status.onRead(lane, event.read)
                                    is EchoLoopEvent.IntegrityBroken -> status.onBroken(lane, event.atByte)
                                    is EchoLoopEvent.Progress -> probe.watch.progressed(event.exchanges)
                                    is EchoLoopEvent.WriteTimedOut -> status.onWriteTimeout(lane, event.owedBytes)
                                    is EchoLoopEvent.Overdue -> status.onOverdue(lane)
                                    is EchoLoopEvent.Failed -> status.onFailure(lane, event.owedBytes)
                                }
                            }
                        when (val end = loop.run(until = minutes.minutes)) {
                            EchoLoopEnd.WalkOver -> Unit
                            is EchoLoopEnd.Left -> status.onEnded(lane, "${end.step.end.label} — reconnecting")
                            is EchoLoopEnd.ConnectionClosed -> status.onEnded(lane, "connection dead (${end.reason}) — reconnecting")
                        }
                    }
                    laneEmit("SCOPE-EXITED cleanly")
                    session.ended(SessionEnd.WalkOver, now())
                } catch (e: Throwable) {
                    laneEmit("CONNECTION-ENDED err=${e::class.simpleName} msg=${e.message}")
                }
                probe.trace.value = Discarded
                val report = session.close(fallback = SessionEnd.ScopeFailed, at = now())
                // Tagged with the family it happened on, so every verdict is attributable to one.
                val tag = target.connectionTag(attempt)
                report.lines(tag).forEach(laneEmit)
                ledger.report(tag, report.liveness)
                probe.totals.absorb(ledger)
                probe.liveness.absorb(attempt, report)
                if (System.currentTimeMillis() < deadline) {
                    // Back off while attempts die young — a flight in airplane mode is hours of
                    // "network unreachable", and a family the network does not carry is a whole walk of
                    // it; a fixed 3 s retry would be thousands of connects for nothing. The failures
                    // are the measurement, so each is still logged. A connection that lived resets the
                    // delay, so the first retry after a real handoff is still prompt.
                    val lived = System.currentTimeMillis() - attemptStarted
                    retryDelayMs = if (lived < SHORT_LIVED_MS) minOf(retryDelayMs * 2, RECONNECT_MAX_MS) else RECONNECT_MIN_MS
                    laneEmit("RECONNECTING in ${retryDelayMs / 1000}s (last attempt lived ${lived}ms)")
                    delay(retryDelayMs)
                }
            }
        }

        runBlocking(Dispatchers.IO) {
            // A heartbeat once a minute, whatever the connections are doing: the process's own memory
            // (the #538 walk died of a native leak nothing logged until the OOM), the battery, and the
            // transport the device is on. Over a multi-day run this is the trend line the echo lines
            // cannot give, and the one line a reader can grep to see the probe was alive at 03:00. One
            // per lane, each beating that lane's own silence watchdog: a live process is not a live
            // echo loop, and one lane going round is not the other going round.
            val heartbeat =
                launch {
                    while (isActive && System.currentTimeMillis() < deadline) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        // Once per beat, whatever the platform did or did not signal: a telephony-only
                        // change on a device that cannot push one is recorded within a minute rather
                        // than never. Nothing is written unless something actually moved.
                        osNet.sample(monitor.state.value)
                        val vitals = "${processMemory()} ${battery(ctx)} ${DiskFree.ofReading(dir.usableSpace).line}"
                        lanes.forEach { probe ->
                            probe.emit("HEARTBEAT attempt=${probe.watch.attempt} $vitals")
                            when (val beat = probe.watch.beat()) {
                                SilenceWatchdog.Beat.Progressing, is SilenceWatchdog.Beat.Quiet -> Unit
                                is SilenceWatchdog.Beat.Stalled -> {
                                    probe.emit(beat.line(probe.watch.attempt, probe.ring.size()))
                                    probe.ring.drain().forEach { probe.emit("STALL-TRACE $it") }
                                    probe.emit("STALL-TRACE-END")
                                }
                                is SilenceWatchdog.Beat.Recovered -> probe.emit(beat.line)
                            }
                        }
                    }
                }
            // Every platform observation is a sample, including the ones NetworkMonitor.state
            // de-dupes away — a link flapping while the rung folds back to itself is exactly when the
            // OS has most to say.
            val osFollow = launch { osNet.follow(monitor) }
            lanes
                .map { probe ->
                    launch {
                        delay(probe.lane.stagger(echoIntervalMs.milliseconds, lanes.size))
                        runLane(probe)
                    }
                }.joinAll()
            osFollow.cancel()
            heartbeat.cancel()
        }
        osSource.unregister()

        if (wakeLock.isHeld) wakeLock.release()
        emit("WAKELOCK released")
        // Each lane's totals and verdicts are its own; the run's line is their sum.
        val run = MigrationTotals()
        lanes.forEach { probe ->
            probe.totals.report(probe.emit, probe.liveness.verdict())
            run.add(probe.totals)
        }
        emit(run.line)
        emit("DONE attempts=${lanes.sumOf { it.watch.attempt }} lanes=${lanes.size} log=${log.absolutePath}")
    }
}

/** What one lane of the walk owns, apart from the connection in flight. */
private class ProbeLane(
    val lane: WalkLane,
    write: (String) -> Unit,
) {
    val emit: (String) -> Unit = lane.log(write)
    val watch = LaneWatch(lane, QUIET_HEARTBEATS_BEFORE_ALARM, HEARTBEAT_INTERVAL_MS.milliseconds)

    /**
     * Where a device-level record goes right now: this lane's live connection's trace, or [Discarded]
     * between connections. The OS network facts are one fact about the phone that every lane's trace
     * needs a copy of, and a lane with no connection open has nowhere to put it — writing it into the
     * file of a connection that already ended would date-stamp a dead record.
     */
    val trace = MutableStateFlow(Discarded)

    // The in-memory tail the lane's stall watchdog dumps inline — see [RingTraceSink]. It is NOT the
    // record of the walk: it holds about a minute and is drained only when the echo loop stops.
    val ring =
        RingTraceSink(
            RING_CAPACITY,
        ) { emit("SEND-STALLED $it — the driver bounded a wedged send and closed the path; a reconnect should follow") }

    // Per-handoff migration capture, rolled up per lane. Without it a green run is over-read: #447
    // only bites when a probe goes UNANSWERED, and on a healthy handoff every probe is answered on the
    // first try. See [MigrationLedger].
    val totals = MigrationTotals()

    // ...and whether the stream those migrations carried was actually being echoed (#620): a path
    // layer that keeps validating while every echo goes unanswered must not read as a pass.
    val liveness = EchoLivenessTotals()
}

/**
 * A live status notification for the walk, one line per lane — because the operator cannot watch
 * logcat while walking, and the one question a walk actually raises is "has it moved yet, and has the
 * new path carried enough traffic that I can go back?", asked of every family.
 *
 * Reactive, not a countdown: every line is driven by an event the probe observed (a path-state change,
 * an echo, an integrity break). The number that answers "can I move on" is **echoes since the last
 * path change** — a migration with two echoes behind it has proven nothing, one with thirty has
 * exercised the new path properly.
 *
 * Best-effort by construction. Notifications need a channel on API 26+ and a runtime grant on API 33+
 * (`adb shell pm grant <test-pkg> android.permission.POST_NOTIFICATIONS`), and this probe's whole
 * contract is that it logs rather than asserts — the recording is the deliverable. So every failure
 * here is swallowed after one line: a device that will not show a notification must still complete the
 * walk it was sent on.
 */
private class ProbeStatus(
    private val ctx: Context,
    private val emit: (String) -> Unit,
    lanes: List<WalkLane>,
) {
    private val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    @Volatile
    private var usable = false
    private val lastPostedAt = AtomicLong()

    /** One lane's line in the shade; each counter is written by that lane's coroutines only. */
    private class LaneStatus {
        val path = AtomicReference("connecting")
        val migrations = AtomicInteger()
        val echoes = AtomicInteger()
        val sinceMove = AtomicInteger()
        val late = AtomicInteger()
        val overdue = AtomicInteger()
        val pending = AtomicInteger()
        val integrity = AtomicReference<Integrity>(Integrity.Intact)
    }

    private sealed interface Integrity {
        data object Intact : Integrity

        data class Broken(
            val atByte: Int,
        ) : Integrity
    }

    private val lanes: Map<String, LaneStatus> = lanes.associate { it.label to LaneStatus() }

    private fun of(lane: WalkLane): LaneStatus = lanes.getValue(lane.label)

    init {
        val mgr = manager
        if (mgr == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            emit("STATUS-NOTIFICATION unavailable (sdk=${Build.VERSION.SDK_INT}) — log only")
        } else {
            usable =
                runCatching {
                    mgr.createNotificationChannel(
                        // DEFAULT, not LOW: One UI files a LOW channel under a collapsed "Silent
                        // notifications" section, where an operator mid-walk will not find it. Paired with
                        // setOnlyAlertOnce so it announces itself once and then updates silently — the
                        // point is to be READABLE at a glance, not to buzz every 2 seconds.
                        NotificationChannel(CHANNEL, "QUIC handoff probe", NotificationManager.IMPORTANCE_DEFAULT),
                    )
                    true
                }.getOrElse {
                    emit("STATUS-NOTIFICATION channel failed: ${it::class.simpleName} ${it.message} — log only")
                    false
                }
        }
        // Post immediately rather than waiting for the first event. A probe that shows nothing until it
        // has connected is indistinguishable, in the shade, from one that never started — and the
        // operator is about to walk away from the machine that could tell them otherwise.
        post()
    }

    fun onPath(
        lane: WalkLane,
        state: String,
    ) {
        val s = of(lane)
        s.path.set(state.substringBefore('(').substringAfterLast('.'))
        if (state.contains("Migrated")) {
            s.migrations.incrementAndGet()
            s.sinceMove.set(0)
        }
        post(force = true)
    }

    fun onRead(
        lane: WalkLane,
        read: EchoRead.Consumed,
    ) {
        val s = of(lane)
        s.echoes.addAndGet(read.outcomes.size)
        s.sinceMove.addAndGet(read.outcomes.size)
        s.late.addAndGet(read.outcomes.count { it is EchoOutcome.Late })
        s.pending.set(read.owedBytes)
        post()
    }

    fun onOverdue(lane: WalkLane) {
        of(lane).overdue.incrementAndGet()
        post()
    }

    fun onWriteTimeout(
        lane: WalkLane,
        owedBytes: Int,
    ) {
        of(lane).pending.set(owedBytes)
        post()
    }

    fun onFailure(
        lane: WalkLane,
        owedBytes: Int,
    ) {
        of(lane).pending.set(owedBytes)
        post(force = true)
    }

    fun onBroken(
        lane: WalkLane,
        atByte: Int,
    ) {
        of(lane).integrity.set(Integrity.Broken(atByte))
        post(force = true)
    }

    fun onEnded(
        lane: WalkLane,
        reason: String,
    ) {
        of(lane).path.set(reason)
        post(force = true)
    }

    /**
     * Repost the shade. Echo-driven updates are rate-limited to one per second: at a 100 ms cadence
     * the first version posted ten notifications a second for the whole run, which the system
     * quietly throttles and which a 72 h run has no business doing. A path change, a break or an
     * end is posted at once — those are what the operator is looking for.
     */
    private fun post(force: Boolean = false) {
        if (!usable) return
        val now = System.currentTimeMillis()
        if (force) {
            lastPostedAt.set(now)
        } else {
            val last = lastPostedAt.get()
            if (now - last < STATUS_MIN_INTERVAL_MS || !lastPostedAt.compareAndSet(last, now)) return
        }
        val breaks =
            lanes.entries.flatMap { (label, s) ->
                when (val integrity = s.integrity.get()) {
                    Integrity.Intact -> emptyList()
                    is Integrity.Broken -> listOf("⚠ STREAM INTEGRITY BROKEN on $label at byte ${integrity.atByte}")
                }
            }
        val title =
            if (breaks.isEmpty()) {
                "QUIC probe · " +
                    lanes.entries.joinToString(" · ") { (label, s) ->
                        val moved = s.migrations.get()
                        if (moved == 0) "$label no migration yet" else "$label $moved migration(s), ${s.sinceMove.get()} echoes since"
                    }
            } else {
                breaks.joinToString(" · ")
            }
        val text =
            lanes.entries.joinToString("\n") { (label, s) ->
                "$label path=${s.path.get()} · echoes=${s.echoes.get()} · late=${s.late.get()} · overdue=${s.overdue.get()} · " +
                    "intact=${if (s.integrity.get() is Integrity.Intact) "yes" else "NO"} · pending=${s.pending.get()}B"
            }
        runCatching {
            val builder =
                Notification
                    .Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
            manager?.notify(NOTIFICATION_ID, builder.build())
        }.onFailure {
            // Almost always a missing POST_NOTIFICATIONS grant on API 33+. Say so once, then stop
            // trying: a walk must not be spent re-throwing the same SecurityException every 2s.
            usable = false
            emit(
                "STATUS-NOTIFICATION post failed: ${it::class.simpleName} ${it.message} — " +
                    "grant with: adb shell pm grant <test-pkg> android.permission.POST_NOTIFICATIONS",
            )
        }
    }

    private companion object {
        // -v2 because a NotificationChannel's importance is IMMUTABLE once created: raising it under the
        // old id is a silent no-op on any device that already ran the probe, and reinstalling does not
        // reset it (only uninstall does). A new id is the only way the bump actually takes effect.
        private const val CHANNEL = "quic-handoff-probe-v2"
        private const val NOTIFICATION_ID = 0x9C1C
    }
}

/**
 * What each migration attempt on ONE connection actually did — the capture that keeps a green field
 * run from being over-read.
 *
 * The run this exists for forces ~30 real Wi-Fi↔cellular handoffs and asks whether the connection
 * survives. That question alone cannot separate the two defects it is meant to validate:
 *
 * - **#445** (a packet bearing a retired CID is dropped, not fatal) is exercised by *any* handoff.
 * - **#447** (a failed probe leaks its spare CID) only bites when a PATH_CHALLENGE goes
 *   **unanswered** — and on a healthy handoff every probe is answered on the first try. So a run of
 *   thirty clean migrations proves #445 and says *nothing* about #447, while reading as if it had
 *   validated both. Field rates: ~18% for #445, #447 seen once.
 *
 * So the ledger records, per attempt, which [QuicPathState] leaf resolved it, and then answers the
 * one question the deterministic suites cannot answer for a real network: **after a probe really
 * died, could this connection still migrate?**
 *
 * The verdict keys on [MigrationResult.Unmoved.Failed.NoSpareConnectionId] rather than on "a later
 * attempt reached `Probing`", for the same reason [FailedProbeConnectionIdTestSuite] does not assert
 * "attempt N reports PathNotValidated": which failure a given attempt reports is timing, but whether
 * the connection can *ever* migrate again is not. It is also the conflation-robust signal —
 * `pathState` is a `StateFlow`, so a `Probing` can be conflated away, whereas `NoSpareConnectionId`
 * is emitted *instead of* probing and is therefore never the state that got skipped.
 *
 * A run in which no probe ever went unanswered is reported **inconclusive**, out loud. That is the
 * whole point: the failure mode this guards against is a silent pass.
 */
private class MigrationLedger(
    private val emit: (String) -> Unit,
) {
    /**
     * One attempt in flight, or none. The old `open` + `openedAt` pair made "closed, with a live
     * timestamp" representable; [lostBefore] additionally records how many probes had already gone
     * unanswered when this attempt opened, which is what makes "after a lost probe" answerable per
     * attempt instead of by a global flag.
     */
    private sealed interface Attempt {
        data object Idle : Attempt

        data class InFlight(
            val n: Int,
            val openedAt: Long,
            val lostBefore: Int,
            val answered: Boolean = false,
        ) : Attempt
    }

    private var attempt: Attempt = Attempt.Idle
    private var attempts = 0
    private var succeeded = 0
    private val leaves = LinkedHashMap<String, Int>()

    /** Attempts resolved by an unanswered PATH_CHALLENGE — the precondition #447 needs to be visible. */
    private var unanswered = 0

    /**
     * …and what happened on the attempts that came AFTER the first one. Only [answeredAfterUnanswered]
     * is recovery: another probe merely being *sent* is the retry ladder of the same failure.
     */
    private var probedAfterUnanswered = 0
    private var answeredAfterUnanswered = 0
    private var succeededAfterUnanswered = 0
    private var noSpareAfterUnanswered = 0

    @Synchronized
    fun onPath(state: QuicPathState) {
        when (state) {
            QuicPathState.Original -> Unit
            is QuicPathState.Probing -> {
                openAttempt()
                if (unanswered > 0) probedAfterUnanswered++
            }
            // The probe was ANSWERED, even though the active path has not switched yet — and being
            // answered is the whole question after a probe has gone unanswered.
            is QuicPathState.Validated -> markAnswered()
            is QuicPathState.Migrated -> {
                openAttempt()
                markAnswered()
                succeeded++
                close("Succeeded")
            }
            is QuicPathState.Failed -> {
                openAttempt()
                val leaf = state.result::class.simpleName ?: "Unknown"
                when (state.result) {
                    MigrationResult.Unmoved.Failed.PathNotValidated -> unanswered++
                    MigrationResult.Unmoved.Failed.NoSpareConnectionId ->
                        if (unanswered > 0) noSpareAfterUnanswered++
                    else -> Unit
                }
                close(leaf)
            }
        }
    }

    /**
     * A terminal state with no `Probing` in front of it still counts as an attempt: `StateFlow`
     * conflates, and the failures that never probe at all ([MigrationResult.Unmoved.Failed
     * .NoSpareConnectionId] above all) are resolved before a probe is ever armed.
     */
    private fun openAttempt() {
        if (attempt is Attempt.InFlight) return
        attempts++
        attempt = Attempt.InFlight(n = attempts, openedAt = System.currentTimeMillis(), lostBefore = unanswered)
    }

    /** Counted once per attempt: a conflated `Validated` -> `Migrated` is one answered probe, not two. */
    private fun markAnswered() {
        val inFlight = attempt as? Attempt.InFlight ?: return
        if (inFlight.answered) return
        attempt = inFlight.copy(answered = true)
        if (inFlight.lostBefore > 0) answeredAfterUnanswered++
    }

    private fun close(leaf: String) {
        val inFlight = attempt as? Attempt.InFlight ?: return
        leaves[leaf] = (leaves[leaf] ?: 0) + 1
        if (inFlight.lostBefore > 0 && leaf == "Succeeded") succeededAfterUnanswered++
        emit("MIGRATION-ATTEMPT n=${inFlight.n} outcome=$leaf tookMs=${System.currentTimeMillis() - inFlight.openedAt}")
        attempt = Attempt.Idle
    }

    private fun history() =
        PoolProbeHistory(
            attempts = attempts,
            unanswered = unanswered,
            probedAfterUnanswered = probedAfterUnanswered,
            answeredAfterUnanswered = answeredAfterUnanswered,
            succeededAfterUnanswered = succeededAfterUnanswered,
            noSpareAfterUnanswered = noSpareAfterUnanswered,
        )

    /** The path layer's verdict, gated by whether the stream it carried was actually echoed (#620). */
    @Synchronized
    fun report(
        tag: String,
        liveness: EchoLivenessVerdict,
    ) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit("MIGRATION-LEDGER $tag attempts=$attempts succeeded=$succeeded outcomes=[$breakdown]")
        emit("447-VERDICT $tag ${verdict().forConnection(liveness).line}")
    }

    @Synchronized
    fun verdict(): PoolRecoveryVerdict = history().verdict()

    @Synchronized
    fun fold(into: MigrationTotals) {
        into.connections++
        into.attempts += attempts
        into.succeeded += succeeded
        into.unanswered += unanswered
        into.probedAfterUnanswered += probedAfterUnanswered
        into.answeredAfterUnanswered += answeredAfterUnanswered
        into.succeededAfterUnanswered += succeededAfterUnanswered
        into.noSpareAfterUnanswered += noSpareAfterUnanswered
        leaves.forEach { (k, v) -> into.leaves[k] = (into.leaves[k] ?: 0) + v }
    }
}

/** One lane's roll-up of every connection's [MigrationLedger], or the run's sum of them — the lines the operator reads at the end. */
private class MigrationTotals {
    var connections = 0
    var attempts = 0
    var succeeded = 0
    var unanswered = 0
    var probedAfterUnanswered = 0
    var answeredAfterUnanswered = 0
    var succeededAfterUnanswered = 0
    var noSpareAfterUnanswered = 0
    val leaves = LinkedHashMap<String, Int>()

    fun absorb(ledger: MigrationLedger) = ledger.fold(this)

    /** Another lane's totals, summed in: every counter is per connection, so the sum is the run's. */
    fun add(other: MigrationTotals) {
        connections += other.connections
        attempts += other.attempts
        succeeded += other.succeeded
        unanswered += other.unanswered
        probedAfterUnanswered += other.probedAfterUnanswered
        answeredAfterUnanswered += other.answeredAfterUnanswered
        succeededAfterUnanswered += other.succeededAfterUnanswered
        noSpareAfterUnanswered += other.noSpareAfterUnanswered
        other.leaves.forEach { (k, v) -> leaves[k] = (leaves[k] ?: 0) + v }
    }

    val line: String
        get() {
            val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
            return "MIGRATION-TOTALS connections=$connections attempts=$attempts succeeded=$succeeded " +
                "unansweredProbes=$unanswered probedAfterUnanswered=$probedAfterUnanswered " +
                "answeredAfterUnanswered=$answeredAfterUnanswered " +
                "noSpareAfterUnanswered=$noSpareAfterUnanswered outcomes=[$breakdown]"
        }

    fun report(
        emit: (String) -> Unit,
        liveness: RunLiveness,
    ) {
        emit(line)
        emit(liveness.line)
        // Every "after" counter only ever advanced inside the connection whose probe was lost, so
        // summing them cannot let a RECONNECT's fresh pool answer for the old one — the over-read
        // this roll-up was caught making after the 2026-08-23 walk.
        val history =
            PoolProbeHistory(
                attempts = attempts,
                unanswered = unanswered,
                probedAfterUnanswered = probedAfterUnanswered,
                answeredAfterUnanswered = answeredAfterUnanswered,
                succeededAfterUnanswered = succeededAfterUnanswered,
                noSpareAfterUnanswered = noSpareAfterUnanswered,
            )
        emit("447-VERDICT run ${history.verdict().forRun(connections, liveness).line}")
    }
}

/** A [QuicCloseException] is the connection closing under the stream; anything else fails one exchange. */
private fun connectionClosedBy(e: Throwable): EchoFailure =
    if (e is QuicCloseException) EchoFailure.ConnectionClosed(e.closeReason.describe()) else EchoFailure.Exchange

/** The probe's QUIC stream as the [EchoLoop] sees it. */
private class QuicEchoStream(
    private val stream: QuicByteStream,
) : EchoStream {
    override suspend fun write(
        payload: String,
        deadline: Duration,
    ): EchoWrite {
        // Write takes no ownership, so this one IS ours to free — and on a multi-hour walk a
        // 10-byte-per-round leak is still a leak.
        val out = BufferFactory.Default.allocate(payload.length)
        try {
            out.writeString(payload, Charset.UTF8)
            out.resetForRead()
            stream.write(out, deadline)
        } catch (e: TimeoutCancellationException) {
            // Only this scope's own cancellation, which wears the same type, gets out of here.
            currentCoroutineContext().ensureActive()
            return EchoWrite.TimedOut
        } finally {
            out.freeIfNeeded()
        }
        return EchoWrite.Written
    }

    // Scoped read (#538): the echoed bytes are decoded inside the block and the buffer is released on
    // the way out.
    override suspend fun read(deadline: Duration): StreamReply =
        try {
            when (val resp = stream.read(deadline) { it.readString(it.remaining(), Charset.UTF8) }) {
                is ScopedRead.Data -> StreamReply.Echoed(resp.value)
                ScopedRead.End -> StreamReply.PeerEnded
                ScopedRead.Reset -> StreamReply.PeerReset
            }
        } catch (e: TimeoutCancellationException) {
            // The deadline is where "late" begins, not where an exchange fails: the reply is still
            // owed and is judged when it arrives.
            currentCoroutineContext().ensureActive()
            StreamReply.StillOwed
        }
}

/**
 * The last [capacity] trace events, and nothing older.
 *
 * The walk's recording could not answer the question it existed to answer: when the probe froze, the
 * final line was an ordinary successful echo and the transport-level detail of what happened next was
 * captured nowhere. A ring is the shape that fits a multi-day run — unbounded capture is gigabytes,
 * and capture that starts after something goes wrong has already missed it.
 */
private class RingTraceSink(
    private val capacity: Int,
    /**
     * Called the instant a stall is recorded, not when the ring is dumped: a stall the driver recovers
     * from never trips the silence watchdog, so the ring holding the evidence is never drained.
     */
    private val onStall: (String) -> Unit,
) : TraceSink {
    private val slots = AtomicReferenceArray<String?>(capacity)
    private val written = AtomicLong(0)

    override fun emit(event: TraceEvent) {
        // Rendered on arrival: holding the events would keep their buffers alive for the ring's life.
        val rendered = event.toString()
        val slot = written.getAndIncrement()
        slots.set((slot % capacity).toInt(), rendered)
        if (event is TraceEvent.Error && event.type.contains("SendStalled")) onStall(rendered)
    }

    fun size(): Int = minOf(written.get(), capacity.toLong()).toInt()

    /** Oldest first, so the dump reads forwards into the moment things stopped. */
    fun drain(): List<String> {
        val total = written.get()
        val count = minOf(total, capacity.toLong())
        val start = total - count
        return (0 until count).mapNotNull { slots.get(((start + it) % capacity).toInt()) }
    }
}

/** How many trace events the post-mortem ring keeps — roughly the last minute of transport activity. */
private const val RING_CAPACITY = 256

/** A lane with no connection open: a device-level record has nowhere to land, so it lands nowhere. */
private val Discarded = TraceSink { }

/** Whether this walk records quiche's qlog, and each lane's budgeted directory for it. */
private sealed interface WalkQlog {
    data object Off : WalkQlog {
        override fun toString(): String = "off"
    }

    /** Every lane writes into [dir], each under its own [QlogDirectory] budget, keyed by lane label. */
    data class Into(
        val dir: File,
        val byLane: Map<String, QlogDirectory>,
    ) : WalkQlog {
        override fun toString(): String = dir.absolutePath
    }
}

/**
 * One file-backed [TraceSink] per connection, under a byte budget every lane shares, and beside it the
 * qlog quiche writes for that connection — both named by the lane and its own connection count,
 * `conn-v6-0003`, so the two records of a connection pair by name.
 *
 * Appends per event rather than holding a writer open: a walk reconnects hundreds of times, and a run
 * killed by a reboot or a pulled cable must not lose its tail. The budget here covers the trace; the
 * qlog's is each lane's [QlogDirectory]'s.
 */
private class WalkTraceFiles(
    private val dir: File,
    private val qlog: WalkQlog,
    lanes: List<WalkLane>,
    private val budgetBytes: Long,
    private val onBudgetSpent: (Long) -> Unit,
) {
    private val connections: Map<String, AtomicInteger> = lanes.associate { it.label to AtomicInteger() }
    private val budget = AtomicReference<TraceSpend>(TraceSpend.Live(0L))

    /** Lock-free: the thread whose CAS moves the budget to [TraceSpend.Spent] is the one that reports it. */
    private fun charge(n: Int): Boolean {
        while (true) {
            when (val current = budget.get()) {
                TraceSpend.Spent -> return false
                is TraceSpend.Live ->
                    if (current.written + n > budgetBytes) {
                        if (budget.compareAndSet(current, TraceSpend.Spent)) {
                            onBudgetSpent(current.written)
                            return false
                        }
                    } else if (budget.compareAndSet(current, TraceSpend.Live(current.written + n))) {
                        return true
                    }
            }
        }
    }

    fun next(lane: WalkLane): QuicConnectionCapture {
        val name = lane.fileStem(connections.getValue(lane.label).incrementAndGet())
        val file = File(dir, "$name.trace")
        val sink =
            TraceSink { event ->
                val line = event.toString() + "\n"
                // The walk is the expensive part; the trace is the instrument. It never takes the walk down.
                if (charge(line.length)) runCatching { file.appendText(line) }
            }
        val target =
            when (qlog) {
                WalkQlog.Off -> QlogTarget.Off
                is WalkQlog.Into -> QlogTarget.Budgeted(qlog.byLane.getValue(lane.label), name)
            }
        return QuicConnectionCapture(sink, target)
    }
}

/**
 * Consecutive heartbeats with no echo-loop progress before the watchdog calls it a stall. Two (~2
 * minutes): past any reconnect backoff (capped at 60s) or a dead radio in a lift, well short of a
 * multi-day run spent unaware. Not fatal — a false alarm costs one log line, a missed real one costs
 * another 72-hour walk.
 */
private const val QUIET_HEARTBEATS_BEFORE_ALARM = 2

private const val HEARTBEAT_INTERVAL_MS = 60_000L
private const val RECONNECT_MIN_MS = 3_000L
private const val RECONNECT_MAX_MS = 60_000L

/** An attempt that ended sooner than this did not get a usable connection; back off before the next. */
private const val SHORT_LIVED_MS = 30_000L
private const val STATUS_MIN_INTERVAL_MS = 1_000L

/** `VmRSS`/`VmSize` of this process, straight from `/proc`, so a leak shows as a trend across heartbeats. */
private fun processMemory(): String =
    runCatching {
        val wanted = setOf("VmRSS", "VmSize", "VmHWM", "Threads")
        File("/proc/self/status")
            .readLines()
            .filter { line -> wanted.any { line.startsWith("$it:") } }
            .joinToString(" ") { it.replace(Regex("\\s+"), "") }
    }.getOrElse { "mem=unreadable(${it::class.simpleName})" }

/** Battery level and whether the device is on power — the two facts that decide whether 72 h is possible. */
private fun battery(ctx: Context): String =
    runCatching {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "battery=unknown"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) (100L * level / scale) else -1
        "battery=$pct% plugged=${if (plugged != 0) "yes" else "no"}"
    }.getOrElse { "battery=unreadable(${it::class.simpleName})" }

/**
 * How much of a walk's trace budget is left.
 *
 * Sealed rather than a `written` counter beside an `announced` flag, because those two admit a state
 * that must not exist: spent-but-not-announced, or announced-then-charged-again. The ceiling is
 * crossed exactly once, and it is the **transition** that announces — so "said so" is not a fact
 * tracked alongside the budget, it is the budget.
 */
private sealed interface TraceSpend {
    /** Room left, [written] bytes used so far. */
    data class Live(
        val written: Long,
    ) : TraceSpend

    /** The ceiling was reached, and reaching it is what reported it. */
    data object Spent : TraceSpend
}

/** What a START found left behind by the run before it, and where it was moved to. */
private sealed interface PreviousRun {
    val line: String

    data object None : PreviousRun {
        override val line: String = "PREVIOUS-RUN none"
    }

    data class Kept(
        val stamp: String,
        val names: List<String>,
    ) : PreviousRun {
        override val line: String get() = "PREVIOUS-RUN kept previous/$stamp/ [${names.joinToString(",")}] — pull.sh collects it"
    }

    companion object {
        fun rotate(
            dir: File,
            names: List<String>,
        ): PreviousRun {
            val leftovers =
                names.map { File(dir, it) }.filter {
                    (it.isFile && it.length() > 0) || (it.isDirectory && !it.listFiles().isNullOrEmpty())
                }
            if (leftovers.isEmpty()) return None
            val stamp =
                java.text
                    .SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date())
            val into = File(dir, "previous/$stamp").apply { mkdirs() }
            leftovers.forEach { it.renameTo(File(into, it.name)) }
            return Kept(stamp, leftovers.map { it.name })
        }
    }
}
