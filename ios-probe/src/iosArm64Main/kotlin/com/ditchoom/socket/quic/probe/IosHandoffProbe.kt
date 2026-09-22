@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class, ExperimentalStdlibApi::class)

package com.ditchoom.socket.quic.probe

import com.ditchoom.socket.testkit.migration.PoolProbeHistory
import com.ditchoom.socket.testkit.migration.forConnection
import com.ditchoom.socket.testkit.migration.forRun
import kotlin.concurrent.AtomicArray
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.MigrationResult
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.describe
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.echo.EchoFailure
import com.ditchoom.socket.testkit.echo.EchoLivenessTotals
import com.ditchoom.socket.testkit.echo.EchoLivenessVerdict
import com.ditchoom.socket.testkit.echo.EchoLoop
import com.ditchoom.socket.testkit.echo.EchoLoopEnd
import com.ditchoom.socket.testkit.echo.EchoLoopEvent
import com.ditchoom.socket.testkit.echo.EchoSession
import com.ditchoom.socket.testkit.echo.EchoStream
import com.ditchoom.socket.testkit.echo.EchoWrite
import com.ditchoom.socket.testkit.echo.RunLiveness
import com.ditchoom.socket.testkit.echo.SessionEnd
import com.ditchoom.socket.testkit.echo.SilenceWatchdog
import com.ditchoom.socket.testkit.echo.StreamReply
import com.ditchoom.socket.testkit.trace.TraceBudget
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.testkit.walk.LaneWatch
import com.ditchoom.socket.testkit.walk.WalkLane
import com.ditchoom.socket.testkit.walk.BuildRevision
import com.ditchoom.socket.testkit.walk.DiskFree
import com.ditchoom.socket.testkit.walk.WalkTargets
import com.ditchoom.socket.testkit.walk.WalkTargetsParse
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryState
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.task_info

/**
 * The iOS half of the real-handoff rig — the Apple counterpart of `DeviceHandoffProbe`.
 *
 * iOS is not a redundant second data point. Apple is the platform where Network.framework was
 * measured **not to re-home a UDP connection** at all: a network change kills the datapath *under*
 * quiche in ~2s (POSIX 57) and it never recovers, which is why the client's datagrams ride a second
 * `NWConnection`. That datapath is a wholly separate code path from Android's, and it is the one a
 * real handoff exercises.
 *
 * Records what a **real** connection does when the path underneath it dies — a walk into an
 * elevator or a garage, not a Wi-Fi toggle. A toggle takes the old path down cleanly while it is
 * still alive, so every PATH_CHALLENGE is answered and #447 is never exercised; a dying path is the
 * only way to get an *unanswered* probe. See [MigrationLedger] for what that distinction buys.
 *
 * Logs rather than asserts, for the same reason the Android probe does: the recording **is** the
 * deliverable, and a probe that threw on the first read timeout would destroy the evidence at the
 * moment it got interesting.
 *
 * Swift drives it — [start] returns immediately and the walk runs on a background dispatcher, while
 * the app polls [status] for the one line an operator mid-walk can act on. The app is also
 * responsible for staying resident: a coroutine `delay` does not keep iOS from suspending the
 * process, so the host app holds a background location session for the duration.
 */
object IosHandoffProbe {
    @Volatile
    private var statusLine: String = "not started"

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var locUpdates: Int = 0

    /** The walk's lanes, published once when it starts; each lane writes only its own status. */
    @Volatile
    private var probeLanes: List<ProbeLane> = emptyList()

    /**
     * Called by the host app on every CoreLocation fix.
     *
     * This is the residency proof, not telemetry. A coroutine `delay` does not keep iOS from
     * suspending the process, so the echo loop and the QUIC keepalive both stall the moment the
     * screen locks — i.e. exactly when the phone is in a pocket recording the walk it was sent on.
     * A background location session is what keeps the process scheduled, and `locUpdates > 0` in the
     * log is the only way to know from the recording that it actually was.
     */
    fun noteLocationUpdate() {
        locUpdates++
    }

    private val startedAt: Double get() = NSDate().timeIntervalSince1970

    private fun now() = NSDate().timeIntervalSince1970.seconds

    private fun documents(): String = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    /** The app's Library: private, unlike Documents, which this app shares with the Files app. */
    private fun library(): String = NSSearchPathForDirectoriesInDomains(NSLibraryDirectory, NSUserDomainMask, true).first() as String

    /** Absolute path of the newline-delimited log, so Swift can offer it to the Files app. */
    fun logPath(): String = "${documents()}/quic-handoff-probe.log"

    /** Directory holding this walk's per-connection replay traces, alongside the log. */
    fun traceDir(): String = "${documents()}/traces"

    /** Directory quiche writes this walk's qlog into, alongside the traces. */
    fun qlogDir(): String = "${documents()}/qlog"

    /** Directory quiche appends each connection's TLS secrets to: they decrypt the traces, so not Documents. */
    fun keysDir(): String = "${library()}/keys"

    /** For the UI: the walk's own state, then one line per lane saying what its connection is doing. */
    fun status(): String = (listOf(statusLine) + probeLanes.map { "${it.lane.label}: ${it.status}" }).joinToString("\n")

    fun isRunning(): Boolean = running

    /**
     * Begin a walk. Returns immediately; the recording runs on [Dispatchers.Default].
     *
     * [hosts] is a comma-separated list, one lane per host, all running at once: each lane keeps its
     * own connection to its own target for the whole walk, so every family is exercised on every
     * network the phone crosses. Lanes, and why a lane never falls back to another family: see
     * [WalkTargets].
     *
     * [echoIntervalMs] defaults to 100ms rather than something leisurely on purpose: at one echo
     * every 2s the connection is essentially **idle** when a handoff lands, and an idle connection
     * has nothing in flight to strand on the path it is leaving — the easy case, reported as a pass.
     */
    fun start(
        hosts: String,
        port: Int,
        minutes: Int,
        echoIntervalMs: Long = 100,
    ) {
        if (running) return
        running = true
        GlobalScope.launch(Dispatchers.Default) {
            try {
                walk(hosts, port, minutes, echoIntervalMs)
            } finally {
                running = false
            }
        }
    }

    private suspend fun walk(
        hosts: String,
        port: Int,
        minutes: Int,
        echoIntervalMs: Long,
    ) {
        val targets =
            when (val parsed = WalkTargets.parse(hosts, port)) {
                // Nothing to walk to, so nothing is recorded: inventing a host would record a walk
                // against a server the operator never named.
                WalkTargetsParse.NoHost -> {
                    statusLine = "no host — launch with -host <ip>[,<ip>]"
                    return
                }
                is WalkTargetsParse.Parsed -> parsed.targets
            }
        // A previous run is never deleted here: it moves under previous/<stamp>/ until pull.sh
        // collects it. Tapping Start used to delete the log, and the trace files would have been
        // appended to across walks.
        val kept = rotatePreviousRun(documents(), listOf("quic-handoff-probe.log", "traces", "qlog"))
        val keptKeys = rotatePreviousRun(library(), listOf("keys"))
        val log = Logger(logPath(), startedAt)
        val diskFreeAtStart = diskFree()
        log.emit(
            "START device=ios ${targets.line} minutes=$minutes echoIntervalMs=$echoIntervalMs qlog=${qlogDir()} keys=${keysDir()} " +
                "${BuildRevision.parse(PROBE_BUILD_STAMP).line} ${diskFreeAtStart.line}",
        )
        log.emit(kept)
        log.emit("KEYS $keptKeys")
        log.emit(targets.lanesLine(echoIntervalMs.milliseconds))
        // quiche's own frame-level record: evidence written by quiche itself, not by this library's
        // code. Each connection writes a run of .sqlog segments named from the same stem as its trace
        // (conn-v6-0003.sqlog beside conn-v6-0003.trace).
        NSFileManager.defaultManager.createDirectoryAtPath(qlogDir(), true, null, null)
        // The TLS secrets that let a lane's traces be decrypted without this library (Wireshark reads
        // conn-v6-0003.keys beside conn-v6-0003.trace), in the app's private Library; pull.sh collects them.
        NSFileManager.defaultManager.createDirectoryAtPath(keysDir(), true, null, null)

        // The record of the walk: one file per connection, appended as it happens, replayable through
        // `TraceToFixture` without re-walking anything. Per connection because the v1 grammar carries
        // no connection id and each connection stamps against its own clock origin — one shared sink
        // interleaves every reconnect into something no fixture can be built from.
        // Sized from this run's own duration, cadence and lanes (see [TraceBudget]) and shared by every
        // lane: at 250 ms the trace is ~8 MB/hour per lane on this phone.
        val budget = TraceBudget.forWalk(minutes, echoIntervalMs.milliseconds, lanes = targets.lanes.size)
        log.emit(budget.line)
        // Each lane keeps its own qlog budget, derived like the trace's and together held to half of
        // what the disk has left once the trace's budget is set aside — this phone's free space cannot
        // be read from outside it, and a full disk would drop qlog writes silently, and this log with
        // them. Every drop is a line of the lane that made it.
        val perLane = QlogBudget.forWalk(minutes, echoIntervalMs.milliseconds, lanes = 1)
        val laneBudget =
            when (diskFreeAtStart) {
                is DiskFree.Known -> perLane.fittedTo((diskFreeAtStart.bytes - budget.bytes) / 2 / targets.lanes.size)
                DiskFree.Unknown -> perLane
            }
        val qlog =
            targets.lanes.associate { lane ->
                val laneEmit = log.forLane(lane)
                laneEmit(laneBudget.line)
                lane.label to QlogDirectory(qlogDir(), laneBudget) { event -> laneEmit(event.line) }
            }
        val traceFiles =
            WalkTraceFiles(traceDir(), qlog, keysDir(), targets.lanes, budget.bytes) { spent ->
                log.emit(
                    "TRACE-BUDGET-SPENT bytes=$spent — trace capture stopped; the walk continues but is no " +
                        "longer replayable past this point.",
                )
            }

        val deadline = startedAt + minutes * 60.0

        // One lane per target, all at once (see [WalkTargets]). Everything a lane counts is its own —
        // attempts, sessions, migration ledgers, backoff, watchdog, stall ring — so one lane's trouble
        // cannot hide in the other's numbers, and nothing is shared between lanes but the log and the
        // trace budget.
        val lanes = targets.lanes.map { ProbeLane(it, log) }
        probeLanes = lanes
        statusLine = "walking · ${lanes.size} lane(s)"

        // A heartbeat once a minute whatever the connections are doing: residency (locUpdates), the
        // process's resident memory, and the battery — the trend line a multi-day log needs, and the
        // one line that says the probe was alive at 03:00 even while the network was gone. One per
        // lane, each beating that lane's own silence watchdog: a live process is not a live echo loop,
        // and one lane going round is not the other going round.
        val heartbeat =
            GlobalScope.launch(Dispatchers.Default) {
                while (NSDate().timeIntervalSince1970 < deadline) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    val vitals = "locUpdates=$locUpdates ${residentMemory()} ${battery()} ${diskFree().line}"
                    lanes.forEach { probe ->
                        probe.emit("HEARTBEAT attempt=${probe.watch.attempt} $vitals")
                        when (val beat = probe.watch.beat()) {
                            SilenceWatchdog.Beat.Progressing, is SilenceWatchdog.Beat.Quiet -> Unit
                            is SilenceWatchdog.Beat.Stalled -> {
                                probe.emit(beat.line(probe.watch.attempt, probe.ring.size()))
                                probe.ring.drain().forEach { probe.emit("STALL-TRACE $it") }
                                probe.emit("STALL-TRACE-END")
                                probe.status = "⚠ STALL SUSPECTED — echo loop has not run"
                            }
                            is SilenceWatchdog.Beat.Recovered -> probe.emit(beat.line)
                        }
                    }
                }
            }
        coroutineScope {
            lanes
                .map { probe ->
                    launch {
                        delay(probe.lane.stagger(echoIntervalMs.milliseconds, lanes.size))
                        runLane(probe, traceFiles, minutes, echoIntervalMs, deadline)
                    }
                }.joinAll()
        }
        heartbeat.cancel()

        // Each lane's totals and verdicts are its own; the run's line is their sum.
        val run = MigrationTotals()
        lanes.forEach { probe ->
            probe.totals.report(probe.emit, probe.liveness.verdict())
            run.add(probe.totals)
        }
        log.emit(run.line)
        log.emit("DONE attempts=${lanes.sumOf { it.watch.attempt }} lanes=${lanes.size} log=${logPath()}")
        statusLine = "done — ${run.attempts} migration attempt(s), ${run.succeeded} succeeded"
    }

    /** One lane's attempts, back to back, until the walk's deadline: its own connection, its own family. */
    private suspend fun runLane(
        probe: ProbeLane,
        traceFiles: WalkTraceFiles,
        minutes: Int,
        echoIntervalMs: Long,
        deadline: Double,
    ) {
        val lane = probe.lane
        val target = lane.target
        val emit = probe.emit
        val options =
            QuicOptions(
                alpnProtocols = listOf("test"),
                verifyPeer = false,
                trace =
                    QuicTraceCapture(
                        captureFor = {
                            val connection = traceFiles.next(lane)
                            // Tee: the lane's ring keeps the cross-connection tail its watchdog needs, the
                            // file keeps this connection's own replayable trace. The qlog rides along.
                            connection.copy(
                                sink =
                                    TraceSink { event ->
                                        probe.ring.emit(event)
                                        connection.sink.emit(event)
                                        // The qlog directory reports its own events; a key log has only this one.
                                        if (event is TraceEvent.TrafficSecretsRefused) emit("TRAFFIC-SECRETS-REFUSED path=${event.path}")
                                    },
                            )
                        },
                        // The connectivity stream is half of every migration question — which trigger
                        // fired, and whether the platform had even noticed the link yet.
                        recordNetworkObservations = true,
                    ),
                // Long enough that a dead path is not immediately reaped, short enough that the walk
                // shows a death rather than a hang. Keepalive keeps an idle connection honest.
                idleTimeout = 30.seconds,
                keepAliveInterval = 5.seconds,
                migration = MigrationPolicy.Automatic,
            )
        var retryDelayMs = RECONNECT_MIN_MS
        while (NSDate().timeIntervalSince1970 < deadline) {
            val attempt = probe.watch.nextAttempt()
            val attemptStarted = NSDate().timeIntervalSince1970
            emit("CONNECT-ATTEMPT n=$attempt ${target.line}")
            // Per CONNECTION, not per walk: a reconnect negotiates a brand-new CID pool, so a pool
            // exhausted on the previous connection says nothing about this one.
            val ledger = MigrationLedger(emit)
            // Per connection too: what is still owed when a connection ends is what failed on it,
            // and how long it went without an answered echo is its own verdict.
            val session = EchoSession(connectedAt = now())
            try {
                withQuicConnection(target.host, target.port, options, timeout = (minutes + 2).minutes) {
                    emit("CONNECTED session=${identity.session} wire=${identity.wire} alpn=$negotiatedAlpn")

                    val stream = openStream()
                    var lastWire = identity.wire
                    probe.watch.connected()

                    // A DEDICATED collector, not a poll: an unanswered path probe is bounded at ~3s
                    // (RFC 9000 §8.2.4), so a whole Probing -> Failed sequence can fall between two
                    // samples of a slow poll and go uncounted.
                    launch {
                        pathState.collect {
                            emit("PATH $it")
                            ledger.onPath(it)
                            probe.status = ledger.oneLine(it.toString())
                        }
                    }

                    val loop =
                        EchoLoop(
                            stream = QuicEchoStream(stream),
                            session = session,
                            interval = echoIntervalMs.milliseconds,
                            clock = ::now,
                            emit = emit,
                            closedBy = ::connectionClosedBy,
                        ) { event ->
                            when (event) {
                                is EchoLoopEvent.Round -> {
                                    if (identity.wire != lastWire) {
                                        emit("WIRE-CID-ROTATED session=${identity.session} wire=${identity.wire}")
                                        lastWire = identity.wire
                                    }
                                    // Periodic residency heartbeat: locUpdates==0 after the screen locks
                                    // means the walk is being recorded by a process iOS has stopped
                                    // scheduling, and every gap in the log below is an artefact rather
                                    // than a network event.
                                    if (event.seq % 600 == 0) {
                                        emit("KEEPALIVE-STATUS echoes=${event.seq} locUpdates=$locUpdates migrations=${ledger.succeeded}")
                                    }
                                }
                                is EchoLoopEvent.IntegrityBroken -> {
                                    probe.status = "⚠ STREAM INTEGRITY BROKEN at byte ${event.atByte}"
                                }
                                is EchoLoopEvent.Progress -> probe.watch.progressed(event.exchanges)
                                is EchoLoopEvent.Read, is EchoLoopEvent.WriteTimedOut, is EchoLoopEvent.Overdue, is EchoLoopEvent.Failed -> Unit
                            }
                        }
                    when (val end = loop.run(until = deadline.seconds)) {
                        EchoLoopEnd.WalkOver -> Unit
                        is EchoLoopEnd.Left -> {
                            probe.status = "${end.step.end.label} — reconnecting"
                        }
                        is EchoLoopEnd.ConnectionClosed -> {
                            probe.status = "connection dead (${end.reason}) — reconnecting"
                        }
                    }
                }
                emit("SCOPE-EXITED cleanly")
                session.ended(SessionEnd.WalkOver, now())
            } catch (e: Throwable) {
                emit("CONNECTION-ENDED err=${e::class.simpleName} msg=${e.message}")
            }
            val report = session.close(fallback = SessionEnd.ScopeFailed, at = now())
            // Tagged with the family it happened on, so every verdict is attributable to one.
            val tag = target.connectionTag(attempt)
            report.lines(tag).forEach(emit)
            ledger.report(tag, report.liveness)
            probe.totals.absorb(ledger)
            probe.liveness.absorb(attempt, report)
            if (NSDate().timeIntervalSince1970 < deadline) {
                // Back off while attempts die young — a flight in airplane mode is hours of "no
                // route", and a family the network does not carry is a whole walk of it; a fixed 3 s
                // retry would be thousands of connects for nothing. The failures are the measurement,
                // so each is still logged. A connection that lived resets the delay.
                val livedMs = ((NSDate().timeIntervalSince1970 - attemptStarted) * 1000).toLong()
                retryDelayMs = if (livedMs < SHORT_LIVED_MS) minOf(retryDelayMs * 2, RECONNECT_MAX_MS) else RECONNECT_MIN_MS
                emit("RECONNECTING in ${retryDelayMs / 1000}s (last attempt lived ${livedMs}ms)")
                delay(retryDelayMs)
            }
        }
    }
}

/** What one lane of the walk owns, apart from the connection in flight. */
private class ProbeLane(
    val lane: WalkLane,
    log: Logger,
) {
    val emit: (String) -> Unit = log.forLane(lane)
    val watch = LaneWatch(lane, QUIET_HEARTBEATS_BEFORE_ALARM, HEARTBEAT_INTERVAL_MS.milliseconds)

    // The in-memory tail the lane's stall watchdog dumps inline. It is NOT the record of the walk: it
    // holds about a minute and is drained only when the echo loop stops.
    val ring =
        RingTraceSink(RING_CAPACITY) { emit("SEND-STALLED $it — the driver bounded a wedged send and closed the path; a reconnect should follow") }

    // Per-handoff migration capture, rolled up per lane: a walk in which every probe is answered
    // proves #445 and says nothing about #447. See [MigrationLedger].
    val totals = MigrationTotals()

    // ...and whether the stream those migrations carried was actually being echoed (#620): a path
    // layer that keeps validating while every echo goes unanswered must not read as a pass.
    val liveness = EchoLivenessTotals()

    /** The lane's line in the app: what its connection is doing right now. */
    @Volatile
    var status: String = "connecting"
}

/**
 * The last [capacity] trace events, and nothing older.
 *
 * Exists because the walk's recording could not answer the one question it was there to answer. When
 * the probe froze, the log's final line was an ordinary successful echo — the transport-level detail
 * of what happened next was never captured anywhere, so the failure had to be reconstructed from its
 * own absence. A ring is the shape that fits a multi-day run: unbounded capture is gigabytes, and
 * capture that starts *after* something goes wrong has already missed it.
 */
private class RingTraceSink(
    private val capacity: Int,
    /**
     * Called the instant a stall is recorded, not when the ring is dumped.
     *
     * Without this the most interesting event in the run is the one thing the log cannot show. A
     * stall the driver *recovers from* never trips the silence watchdog — the loop is running again
     * within seconds — so the ring holding the evidence is never dumped, and the recovery reads as
     * one more ordinary reconnect among hundreds. The whole point of this run is to catch the fix
     * working, which means the stall has to announce itself when it happens.
     */
    private val onStall: (String) -> Unit,
) : TraceSink {
    // Lock-free: the transport's trace thread writes while the heartbeat drains.
    private val slots = AtomicArray<String>(capacity) { "" }
    private val written = AtomicLong(0)

    override fun emit(event: TraceEvent) {
        // Rendered on arrival: the event's own encoding is what a post-mortem reads, and holding the
        // objects would keep their buffers alive for the life of the ring.
        val rendered = event.toString()
        val slot = written.getAndIncrement()
        slots[(slot % capacity).toInt()] = rendered
        if (event is TraceEvent.Error && event.type.contains("SendStalled")) onStall(rendered)
    }

    fun size(): Int = minOf(written.value, capacity.toLong()).toInt()

    /** Oldest first, so the dump reads forwards into the moment things stopped. */
    fun drain(): List<String> {
        val total = written.value
        val count = minOf(total, capacity.toLong())
        val start = total - count
        return (0 until count).map { slots[((start + it) % capacity).toInt()] }
    }
}

/**
 * Newline-delimited log in the app's Documents directory, where the Files app and `devicectl` can
 * both reach it.
 *
 * Appends through [NSFileHandle] rather than rewriting the file: at a 100ms cadence a two-hour walk
 * is tens of thousands of lines, and an atomic whole-file write per line would be quadratic.
 */
private class Logger(
    private val path: String,
    private val startedAt: Double,
) {
    /**
     * A line of the whole run's. Every line names who it belongs to, so an analyzer that does not know
     * lanes reads nothing it recognises instead of two lanes merged into one.
     */
    fun emit(line: String) = write("${WalkLane.RUN_TOKEN} $line")

    /** [lane]'s own lines. */
    fun forLane(lane: WalkLane): (String) -> Unit = lane.log(::write)

    private fun write(line: String) {
        val t = ((NSDate().timeIntervalSince1970 - startedAt) * 1000).toLong()
        val rendered = "t=${t}ms $line"
        // Console.app / `log stream` too, so a tethered start is watchable before the walk begins.
        NSLog("QuicHandoffProbe %s", rendered)
        // POSIX append rather than Foundation: `NSFileHandle`'s class factories are not exposed by
        // the Kotlin/Native Foundation bindings, and an atomic whole-file rewrite per line would be
        // quadratic — a two-hour walk at a 100ms cadence is tens of thousands of lines.
        val file = fopen(path, "a") ?: return
        fputs(rendered + "\n", file)
        fclose(file)
    }
}

/**
 * What each migration attempt on ONE connection actually did — the capture that keeps a green walk
 * from being over-read. Mirrors the Android probe's ledger deliberately, so the two platforms'
 * recordings can be read side by side.
 *
 * A walk exercises **#445** on any handoff at all, but **#447** only bites when a PATH_CHALLENGE
 * goes **unanswered**. So a walk in which every probe is answered proves #445 and says *nothing*
 * about #447 — while reading exactly as if it had validated both. This says so out loud instead.
 *
 * The verdict keys on [MigrationResult.Unmoved.Failed.NoSpareConnectionId] rather than on "a later
 * attempt reached `Probing`": which failure a given attempt reports is timing, but whether the
 * connection can *ever* migrate again is not. It is also the conflation-robust signal — `pathState`
 * is a `StateFlow`, so a `Probing` can be conflated away, whereas `NoSpareConnectionId` is emitted
 * *instead of* probing and is therefore never the state that got skipped.
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
            val openedAt: Double,
            val lostBefore: Int,
            val answered: Boolean = false,
        ) : Attempt
    }

    var attempts = 0
        private set
    var succeeded = 0
        private set
    private var attempt: Attempt = Attempt.Idle
    private val leaves = mutableMapOf<String, Int>()
    private var unanswered = 0
    private var probedAfterUnanswered = 0
    private var answeredAfterUnanswered = 0
    private var succeededAfterUnanswered = 0
    private var noSpareAfterUnanswered = 0

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
                when (state.result) {
                    MigrationResult.Unmoved.Failed.PathNotValidated -> unanswered++
                    MigrationResult.Unmoved.Failed.NoSpareConnectionId ->
                        if (unanswered > 0) noSpareAfterUnanswered++
                    else -> Unit
                }
                close(state.result::class.simpleName ?: "Unknown")
            }
        }
    }

    /**
     * A terminal state with no `Probing` in front of it still counts as an attempt: `StateFlow`
     * conflates, and the failures that never probe at all resolve before a probe is ever armed.
     */
    private fun openAttempt() {
        if (attempt is Attempt.InFlight) return
        attempts++
        attempt = Attempt.InFlight(n = attempts, openedAt = NSDate().timeIntervalSince1970, lostBefore = unanswered)
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
        val took = ((NSDate().timeIntervalSince1970 - inFlight.openedAt) * 1000).toLong()
        emit("MIGRATION-ATTEMPT n=${inFlight.n} outcome=$leaf tookMs=$took")
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

    /** The one line an operator mid-walk can act on: has it moved, and has the new path carried traffic. */
    fun oneLine(path: String): String = "$succeeded migration(s) · path=${path.substringBefore('(')} · unanswered probes=$unanswered"

    /** The path layer's verdict, gated by whether the stream it carried was actually echoed (#620). */
    fun report(
        tag: String,
        liveness: EchoLivenessVerdict,
    ) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit("MIGRATION-LEDGER $tag attempts=$attempts succeeded=$succeeded outcomes=[$breakdown]")
        emit("447-VERDICT $tag ${history().verdict().forConnection(liveness).line}")
    }

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

/** One lane's roll-up of every connection's [MigrationLedger], or the walk's sum of them. */
private class MigrationTotals {
    var connections = 0
    var attempts = 0
    var succeeded = 0
    var unanswered = 0
    var probedAfterUnanswered = 0
    var answeredAfterUnanswered = 0
    var succeededAfterUnanswered = 0
    var noSpareAfterUnanswered = 0
    val leaves = mutableMapOf<String, Int>()

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
        // summing them cannot let a RECONNECT's fresh pool answer for the old one.
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

private const val HEARTBEAT_INTERVAL_MS = 60_000L

/** Free space on the volume holding this app's Documents, as the file system reports it. */
private fun diskFree(): DiskFree {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    val free = NSFileManager.defaultManager.attributesOfFileSystemForPath(documents, null)?.get(NSFileSystemFreeSize) as? NSNumber
    return if (free == null) DiskFree.Unknown else DiskFree.ofReading(free.longLongValue)
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
        // Write takes no ownership, so this buffer is ours to free; read transfers it, so the scoped
        // form frees that one for us (#538) — a K/N buffer is explicitly freed with no collector
        // behind it at all.
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
 * How many trace events the post-mortem ring keeps. At this cadence roughly the last minute of
 * transport activity — enough to show what the datapath was doing as it stopped, without holding
 * megabytes of hex for a run measured in days.
 */
private const val RING_CAPACITY = 256

/**
 * One file-backed [TraceSink] per connection, under a byte budget every lane shares, and beside it the
 * qlog and key log quiche writes for that connection — all named by the lane and its own connection count,
 * `conn-v6-0003`, so a connection's records pair by name.
 *
 * Appends per event rather than holding a writer open: a walk reconnects hundreds of times, and a run
 * killed by a reboot or a pulled cable must not lose its tail. The budget here covers the trace; the
 * qlog's is each lane's [QlogDirectory]'s, keyed by lane label in [qlog].
 */
private class WalkTraceFiles(
    private val dir: String,
    private val qlog: Map<String, QlogDirectory>,
    private val keysDir: String,
    lanes: List<WalkLane>,
    private val budgetBytes: Long,
    private val onBudgetSpent: (Long) -> Unit,
) {
    private val connections: Map<String, AtomicInt> = lanes.associate { it.label to AtomicInt(0) }
    private val budget = AtomicReference<TraceSpend>(TraceSpend.Live(0L))

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    }

    /** Lock-free: the thread whose CAS moves the budget to [TraceSpend.Spent] is the one that reports it. */
    private fun charge(n: Int): Boolean {
        while (true) {
            when (val current = budget.value) {
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
        val path = "$dir/$name.trace"
        val sink =
            TraceSink { event ->
                val line = event.toString() + "\n"
                // The walk is the expensive part; the trace is the instrument. It never takes the walk down.
                if (charge(line.length)) {
                    val file = fopen(path, "a")
                    if (file != null) {
                        fputs(line, file)
                        fclose(file)
                    }
                }
            }
        return QuicConnectionCapture(
            sink,
            QlogTarget.Budgeted(qlog.getValue(lane.label), name),
            TrafficSecretsLog.File("$keysDir/$name.keys"),
        )
    }
}

/**
 * Consecutive heartbeats with no echo-loop progress before the watchdog calls it a stall.
 *
 * Two, i.e. ~2 minutes: long enough that a reconnect backoff (capped at 60s) or a dead radio in an
 * elevator cannot trip it, short enough that a multi-day run is not spent unaware. The alarm is not
 * fatal — the probe keeps recording either way, because a false alarm that costs one log line is a
 * far better trade than a real one that costs another 72-hour walk.
 */
private const val QUIET_HEARTBEATS_BEFORE_ALARM = 2
private const val RECONNECT_MIN_MS = 3_000L
private const val RECONNECT_MAX_MS = 60_000L

/** An attempt that ended sooner than this did not get a usable connection; back off before the next. */
private const val SHORT_LIVED_MS = 30_000L

/** This process's resident set, from Mach, so a leak shows as a trend across heartbeats. */
@OptIn(ExperimentalForeignApi::class)
private fun residentMemory(): String =
    runCatching {
        memScoped {
            val info = alloc<mach_task_basic_info>()
            val count = alloc<mach_msg_type_number_tVar>()
            count.value = (sizeOf<mach_task_basic_info>() / sizeOf<IntVar>()).toUInt()
            val rc = task_info(mach_task_self_, MACH_TASK_BASIC_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
            if (rc == KERN_SUCCESS) "rss=${info.resident_size / 1024u}kB vsz=${info.virtual_size / (1024u * 1024u)}MB" else "rss=unreadable(kern=$rc)"
        }
    }.getOrElse { "rss=unreadable(${it::class.simpleName})" }

/** Battery level and state; monitoring is switched on once, on first use. */
private fun battery(): String =
    runCatching {
        val device = UIDevice.currentDevice
        if (!device.batteryMonitoringEnabled) device.batteryMonitoringEnabled = true
        val level = (device.batteryLevel * 100).toInt()
        val state =
            when (device.batteryState) {
                UIDeviceBatteryState.UIDeviceBatteryStateCharging -> "charging"
                UIDeviceBatteryState.UIDeviceBatteryStateFull -> "full"
                UIDeviceBatteryState.UIDeviceBatteryStateUnplugged -> "unplugged"
                else -> "unknown"
            }
        "battery=$level% $state"
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
    data class Live(val written: Long) : TraceSpend

    /** The ceiling was reached, and reaching it is what reported it. */
    data object Spent : TraceSpend
}

/**
 * Move whatever the previous walk left under [documents] into `previous/<stamp>/`, and say so in the
 * grammar the log is grepped for. Nothing is deleted: `pull.sh` collects it.
 */
private fun rotatePreviousRun(
    documents: String,
    names: List<String>,
): String {
    val fm = NSFileManager.defaultManager
    val leftovers = names.filter { fm.fileExistsAtPath("$documents/$it") }
    if (leftovers.isEmpty()) return "PREVIOUS-RUN none"
    val stamp = NSDate().timeIntervalSince1970.toLong().toString()
    val into = "$documents/previous/$stamp"
    fm.createDirectoryAtPath(into, true, null, null)
    leftovers.forEach { fm.moveItemAtPath("$documents/$it", "$into/$it", null) }
    return "PREVIOUS-RUN kept previous/$stamp/ [${leftovers.joinToString(",")}] — pull.sh collects it"
}
