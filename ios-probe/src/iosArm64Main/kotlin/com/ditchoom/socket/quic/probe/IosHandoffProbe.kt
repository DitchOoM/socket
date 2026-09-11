@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)

package com.ditchoom.socket.quic.probe

import com.ditchoom.socket.testkit.migration.runLine
import com.ditchoom.socket.testkit.migration.PoolProbeHistory
import kotlin.concurrent.AtomicReference
import kotlin.concurrent.AtomicInt
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.MigrationResult
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.describe
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPathState
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.remove
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import kotlin.concurrent.Volatile
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

    /**
     * Bumped once per echo-loop iteration, whatever the outcome — OK, no-data, or failure.
     *
     * The walk that motivated this recorded 48.6 hours in which this loop produced **nothing at all**:
     * not an echo, not a timeout, not an error. Every existing counter measured what the loop *did*,
     * so a loop that did nothing was indistinguishable from one that was never asked to. This one
     * measures that it *ran*, which is what the heartbeat compares against to notice its own silence.
     */
    @Volatile
    private var loopTicks: Int = 0

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

    /** Absolute path of the newline-delimited log, so Swift can offer it to the Files app. */
    fun logPath(): String {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        return "$docs/quic-handoff-probe.log"
    }

    /** Directory holding this walk's per-connection replay traces, alongside the log. */
    fun traceDir(): String {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        return "$docs/traces"
    }

    /** One line for the UI: what the connection is doing right now. */
    fun status(): String = statusLine

    fun isRunning(): Boolean = running

    /**
     * Begin a walk. Returns immediately; the recording runs on [Dispatchers.Default].
     *
     * [echoIntervalMs] defaults to 100ms rather than something leisurely on purpose: at one echo
     * every 2s the connection is essentially **idle** when a handoff lands, and an idle connection
     * has nothing in flight to strand on the path it is leaving — the easy case, reported as a pass.
     */
    fun start(
        host: String,
        port: Int,
        minutes: Int,
        readTimeoutMs: Long = 400,
        echoIntervalMs: Long = 100,
    ) {
        if (running) return
        running = true
        GlobalScope.launch(Dispatchers.Default) {
            try {
                walk(host, port, minutes, readTimeoutMs, echoIntervalMs)
            } finally {
                running = false
            }
        }
    }

    private suspend fun walk(
        host: String,
        port: Int,
        minutes: Int,
        readTimeoutMs: Long,
        echoIntervalMs: Long,
    ) {
        val log = Logger(logPath(), startedAt)
        log.reset()
        log.emit(
            "START device=ios target=$host:$port minutes=$minutes " +
                "readTimeoutMs=$readTimeoutMs echoIntervalMs=$echoIntervalMs",
        )

        // The in-memory tail the stall watchdog dumps inline. It is NOT the record of the walk: it
        // holds about a minute and is drained only when the echo loop stops, so a migration the
        // connection *recovers from* never reached the log at all.
        //
        // The "tens of gigabytes, so persisting is out of the question" that used to be written here
        // was measured against a saturated connection, not against this rig. The echo loop is one
        // exchange every `echoIntervalMs` (2s default) carrying a ~12-byte payload, so the trace runs
        // ~1.5 MB/hour and a 71-hour walk is ~100 MB. Persisting it was never the problem; assuming it
        // could not be was.
        val ring = RingTraceSink(RING_CAPACITY) { log.emit("SEND-STALLED $it — the driver bounded a wedged send and closed the path; a reconnect should follow") }

        // The record of the walk: one file per connection, appended as it happens, replayable through
        // `TraceToFixture` without re-walking anything. Per connection because the v1 grammar carries
        // no connection id and each connection stamps against its own clock origin — one shared sink
        // interleaves every reconnect into something no fixture can be built from.
        val traceFiles =
            WalkTraceFiles(traceDir(), TRACE_BUDGET_BYTES) { spent ->
                log.emit(
                    "TRACE-BUDGET-SPENT bytes=$spent — trace capture stopped; the walk continues but is no " +
                        "longer replayable past this point.",
                )
            }

        val options =
            QuicOptions(
                alpnProtocols = listOf("test"),
                verifyPeer = false,
                trace =
                    QuicTraceCapture(
                        sinkFor = {
                            val file = traceFiles.next()
                            // Tee: the ring keeps the cross-connection tail the watchdog needs, the file
                            // keeps this connection's own replayable trace.
                            TraceSink { event ->
                                ring.emit(event)
                                file.emit(event)
                            }
                        },
                        // The connectivity stream is half of every migration question — which trigger
                        // fired, and whether the platform had even noticed the link yet. It was off.
                        recordNetworkObservations = true,
                    ),
                // Long enough that a dead path is not immediately reaped, short enough that the walk
                // shows a death rather than a hang. Keepalive keeps an idle connection honest.
                idleTimeout = 30.seconds,
                keepAliveInterval = 5.seconds,
                migration = MigrationPolicy.Automatic,
            )

        val deadline = startedAt + minutes * 60.0
        val totals = MigrationTotals()
        var attempt = 0

        // A heartbeat once a minute whatever the connection is doing: residency (locUpdates), the
        // process's resident memory, and the battery — the trend line a multi-day log needs, and the
        // one line that says the probe was alive at 03:00 even while the network was gone.
        val heartbeat =
            GlobalScope.launch(Dispatchers.Default) {
                var lastTicks = -1
                var quietHeartbeats = 0
                var dumped = false
                while (NSDate().timeIntervalSince1970 < deadline) {
                    delay(HEARTBEAT_INTERVAL_MS)
                    log.emit("HEARTBEAT attempt=$attempt locUpdates=$locUpdates ${residentMemory()} ${battery()}")

                    // The silence watchdog. A heartbeat that only says "the process is alive" is what
                    // let a wedged echo loop look healthy for two days: the process WAS alive, and
                    // that was the least interesting true thing about it. Comparing [loopTicks]
                    // against the previous heartbeat asks the question that actually matters — is the
                    // loop still going round? — and answers it in the recording rather than leaving it
                    // to be inferred, months later, from the absence of lines.
                    val ticks = loopTicks
                    if (ticks == lastTicks) {
                        quietHeartbeats++
                        if (quietHeartbeats >= QUIET_HEARTBEATS_BEFORE_ALARM && !dumped) {
                            dumped = true
                            log.emit(
                                "STALL-SUSPECTED loopTicks=$ticks unchanged for " +
                                    "${quietHeartbeats * (HEARTBEAT_INTERVAL_MS / 1000)}s attempt=$attempt — " +
                                    "the echo loop is not running. Dumping the last ${ring.size()} trace events.",
                            )
                            ring.drain().forEach { log.emit("STALL-TRACE $it") }
                            log.emit("STALL-TRACE-END")
                            statusLine = "\u26a0 STALL SUSPECTED — echo loop has not run"
                        }
                    } else {
                        if (dumped) log.emit("STALL-RECOVERED loopTicks=$ticks after ${quietHeartbeats} quiet heartbeat(s)")
                        lastTicks = ticks
                        quietHeartbeats = 0
                        dumped = false
                    }
                }
            }
        var retryDelayMs = RECONNECT_MIN_MS
        while (NSDate().timeIntervalSince1970 < deadline) {
            attempt++
            val attemptStarted = NSDate().timeIntervalSince1970
            log.emit("CONNECT-ATTEMPT n=$attempt")
            // Per CONNECTION, not per walk: a reconnect negotiates a brand-new CID pool, so a pool
            // exhausted on the previous connection says nothing about this one.
            val ledger = MigrationLedger(log::emit)
            try {
                withQuicConnection(host, port, options, timeout = (minutes + 2).minutes) {
                    log.emit("CONNECTED session=${identity.session} wire=${identity.wire} alpn=$negotiatedAlpn")

                    val stream = openStream()
                    var seq = 0
                    var lastWire = identity.wire

                    // Everything received must be an exact, in-order PREFIX of everything sent. Late
                    // delivery keeps that true; bytes destroyed by a timed-out read break it
                    // permanently, because the stream then resumes past the hole.
                    // Kept as the bytes sent but NOT YET echoed, not as two ever-growing histories:
                    // `sentAll.startsWith(recvAll)` on every echo is O(total) per echo and O(total²)
                    // over a run — fine for a 2 h walk, fatal for a 72 h one (millions of echoes).
                    val pendingSent = StringBuilder()
                    var integrityBroken = false

                    // A DEDICATED collector, not a poll: an unanswered path probe is bounded at ~3s
                    // (RFC 9000 §8.2.4), so a whole Probing -> Failed sequence can fall between two
                    // samples of a slow poll and go uncounted.
                    launch {
                        pathState.collect {
                            log.emit("PATH $it")
                            ledger.onPath(it)
                            statusLine = ledger.oneLine(it.toString())
                        }
                    }

                    while (NSDate().timeIntervalSince1970 < deadline) {
                        if (identity.wire != lastWire) {
                            log.emit("WIRE-CID-ROTATED session=${identity.session} wire=${identity.wire}")
                            lastWire = identity.wire
                        }

                        seq++
                        // Before anything that can park, so a loop wedged *inside* an iteration still
                        // shows the tick that proves it got that far.
                        loopTicks++
                        // Periodic residency heartbeat: locUpdates==0 after the screen locks means
                        // the walk is being recorded by a process iOS has stopped scheduling, and
                        // every gap in the log below is an artefact rather than a network event.
                        if (seq % 600 == 0) {
                            log.emit("KEEPALIVE-STATUS echoes=$seq locUpdates=$locUpdates migrations=${ledger.succeeded}")
                        }
                        val sentAt = NSDate().timeIntervalSince1970
                        val payload = "probe-$seq;"
                        try {
                            // Write takes no ownership, so this buffer is ours to free; read transfers
                            // it, so the scoped form frees that one for us (#538). The Android sibling
                            // of this loop did neither and died at VmSize 20.8 GB, 2 h 36 m into a
                            // 5-hour walk — a K/N buffer is explicitly freed with no collector behind
                            // it at all, so this probe had even less to fall back on.
                            val out = BufferFactory.Default.allocate(payload.length)
                            try {
                                out.writeString(payload, Charset.UTF8)
                                out.resetForRead()
                                stream.write(out, 5.seconds)
                            } finally {
                                out.freeIfNeeded()
                            }
                            pendingSent.append(payload)
                            val resp = stream.read(readTimeoutMs.milliseconds) { it.readString(it.remaining(), Charset.UTF8) }
                            val rtt = ((NSDate().timeIntervalSince1970 - sentAt) * 1000).toLong()
                            if (resp is ScopedRead.Data) {
                                val echoed = resp.value
                                val intact =
                                    echoed.length <= pendingSent.length &&
                                        pendingSent.regionMatches(0, echoed, 0, echoed.length)
                                if (intact) pendingSent.deleteRange(0, echoed.length)
                                val pending = pendingSent.length
                                log.emit("ECHO-OK seq=$seq rtt=${rtt}ms got=${echoed.length}B intact=$intact pending=${pending}B")
                                if (!intact && !integrityBroken) {
                                    integrityBroken = true
                                    val at = echoed.indices.firstOrNull { it >= pendingSent.length || pendingSent[it] != echoed[it] } ?: 0
                                    log.emit(
                                        "STREAM-INTEGRITY-BROKEN seq=$seq atByte=$at " +
                                            "expected=[${pendingSent.substring(0, minOf(pendingSent.length, at + 24))}] " +
                                            "recv=[${echoed.substring(0, minOf(echoed.length, at + 24))}]",
                                    )
                                    statusLine = "⚠ STREAM INTEGRITY BROKEN at byte $at"
                                }
                            } else {
                                log.emit("ECHO-NO-DATA seq=$seq after=${rtt}ms result=$resp")
                            }
                        } catch (e: Throwable) {
                            // The interesting case. Record and keep going — the connection may still
                            // be alive and migrating underneath us.
                            log.emit(
                                "ECHO-FAIL seq=$seq after=${((NSDate().timeIntervalSince1970 - sentAt) * 1000).toLong()}ms " +
                                    "err=${e::class.simpleName} msg=${e.message}",
                            )
                            // ...unless it is DEAD, in which case "keep going" means spinning against
                            // a closed connection for the rest of the walk. Leave the scope and let
                            // the outer loop reconnect — a reconnect is itself data, being precisely
                            // what distinguishes "migrated" from "had to start over".
                            if (e is QuicCloseException) {
                                val why = e.closeReason.describe()
                                log.emit("CONNECTION-DEAD seq=$seq reason=$why — leaving scope to reconnect")
                                statusLine = "connection dead ($why) — reconnecting"
                                return@withQuicConnection
                            }
                        }
                        delay(echoIntervalMs)
                    }
                }
                log.emit("SCOPE-EXITED cleanly")
            } catch (e: Throwable) {
                log.emit("CONNECTION-ENDED err=${e::class.simpleName} msg=${e.message}")
            }
            ledger.report("connection=$attempt")
            totals.absorb(ledger)
            if (NSDate().timeIntervalSince1970 < deadline) {
                // Back off while attempts die young — a flight in airplane mode is hours of "no
                // route", and a fixed 3 s retry would be thousands of connects for nothing. A
                // connection that lived resets the delay, so the retry after a real handoff is prompt.
                val livedMs = ((NSDate().timeIntervalSince1970 - attemptStarted) * 1000).toLong()
                retryDelayMs = if (livedMs < SHORT_LIVED_MS) minOf(retryDelayMs * 2, RECONNECT_MAX_MS) else RECONNECT_MIN_MS
                log.emit("RECONNECTING in ${retryDelayMs / 1000}s (last attempt lived ${livedMs}ms)")
                delay(retryDelayMs)
            }
        }
        heartbeat.cancel()

        totals.report(log::emit)
        log.emit("DONE attempts=$attempt log=${logPath()}")
        statusLine = "done — ${totals.attempts} migration attempt(s), ${totals.succeeded} succeeded"
    }
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
    private val slots = arrayOfNulls<String>(capacity)
    private var next = 0
    private var filled = 0

    override fun emit(event: TraceEvent) {
        // Rendered on arrival: the event's own encoding is what a post-mortem reads, and holding the
        // objects would keep their buffers alive for the life of the ring.
        val rendered = event.toString()
        slots[next] = rendered
        next = (next + 1) % capacity
        if (filled < capacity) filled++
        if (event is TraceEvent.Error && event.type.contains("SendStalled")) onStall(rendered)
    }

    fun size(): Int = filled

    /** Oldest first, so the dump reads forwards into the moment things stopped. */
    fun drain(): List<String> {
        val start = if (filled < capacity) 0 else next
        return (0 until filled).mapNotNull { slots[(start + it) % capacity] }
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
    fun reset() {
        remove(path)
    }

    fun emit(line: String) {
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

    fun report(tag: String) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit("MIGRATION-LEDGER $tag attempts=$attempts succeeded=$succeeded outcomes=[$breakdown]")
        emit("447-VERDICT $tag ${history().verdict().line}")
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

/** Walk-wide roll-up of every connection's [MigrationLedger]. */
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

    fun report(emit: (String) -> Unit) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit(
            "MIGRATION-TOTALS connections=$connections attempts=$attempts succeeded=$succeeded " +
                "unansweredProbes=$unanswered probedAfterUnanswered=$probedAfterUnanswered " +
                "answeredAfterUnanswered=$answeredAfterUnanswered " +
                "noSpareAfterUnanswered=$noSpareAfterUnanswered outcomes=[$breakdown]",
        )
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
        emit("447-VERDICT walk ${history.verdict().runLine(connections)}")
    }
}

private const val HEARTBEAT_INTERVAL_MS = 60_000L

/**
 * How many trace events the post-mortem ring keeps. At this cadence roughly the last minute of
 * transport activity — enough to show what the datapath was doing as it stopped, without holding
 * megabytes of hex for a run measured in days.
 */
private const val RING_CAPACITY = 256

/** Ceiling on a single walk's replay traces — see [WalkTraceFiles]. ~5x the 71-hour projection. */
private const val TRACE_BUDGET_BYTES = 512L * 1024L * 1024L

/**
 * One file-backed [TraceSink] per connection, under a shared byte budget.
 *
 * Appends per event rather than holding a writer open: a walk reconnects hundreds of times, and a run
 * killed by a reboot or a pulled cable must not lose its tail.
 */
private class WalkTraceFiles(
    private val dir: String,
    private val budgetBytes: Long,
    private val onBudgetSpent: (Long) -> Unit,
) {
    private val connections = AtomicInt(0)
    private val budget = AtomicReference<TraceBudget>(TraceBudget.Live(0L))

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    }

    /** Lock-free: the thread whose CAS moves the budget to [TraceBudget.Spent] is the one that reports it. */
    private fun charge(n: Int): Boolean {
        while (true) {
            when (val current = budget.value) {
                TraceBudget.Spent -> return false
                is TraceBudget.Live ->
                    if (current.written + n > budgetBytes) {
                        if (budget.compareAndSet(current, TraceBudget.Spent)) {
                            onBudgetSpent(current.written)
                            return false
                        }
                    } else if (budget.compareAndSet(current, TraceBudget.Live(current.written + n))) {
                        return true
                    }
            }
        }
    }

    fun next(): TraceSink {
        val path = "$dir/conn-" + connections.incrementAndGet().toString().padStart(4, '0') + ".trace"
        return TraceSink { event ->
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
private sealed interface TraceBudget {
    /** Room left, [written] bytes used so far. */
    data class Live(val written: Long) : TraceBudget

    /** The ceiling was reached, and reaching it is what reported it. */
    data object Spent : TraceBudget
}
