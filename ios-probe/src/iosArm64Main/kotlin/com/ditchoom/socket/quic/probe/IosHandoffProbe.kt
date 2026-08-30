@file:OptIn(ExperimentalForeignApi::class, DelicateCoroutinesApi::class)

package com.ditchoom.socket.quic.probe

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

        val options =
            QuicOptions(
                alpnProtocols = listOf("test"),
                verifyPeer = false,
                // Long enough that a dead path is not immediately reaped, short enough that the walk
                // shows a death rather than a hang. Keepalive keeps an idle connection honest.
                idleTimeout = 30.seconds,
                keepAliveInterval = 5.seconds,
                migration = MigrationPolicy.Automatic,
            )

        val deadline = startedAt + minutes * 60.0
        val totals = MigrationTotals()
        var attempt = 0

        while (NSDate().timeIntervalSince1970 < deadline) {
            attempt++
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
                    val sentAll = StringBuilder()
                    val recvAll = StringBuilder()
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
                            sentAll.append(payload)
                            val resp = stream.read(readTimeoutMs.milliseconds) { it.readString(it.remaining(), Charset.UTF8) }
                            val rtt = ((NSDate().timeIntervalSince1970 - sentAt) * 1000).toLong()
                            if (resp is ScopedRead.Data) {
                                val echoed = resp.value
                                recvAll.append(echoed)
                                val intact = sentAll.startsWith(recvAll)
                                val pending = sentAll.length - recvAll.length
                                log.emit("ECHO-OK seq=$seq rtt=${rtt}ms got=${echoed.length}B intact=$intact pending=${pending}B")
                                if (!intact && !integrityBroken) {
                                    integrityBroken = true
                                    val at = recvAll.indices.firstOrNull { it >= sentAll.length || sentAll[it] != recvAll[it] } ?: 0
                                    log.emit("STREAM-INTEGRITY-BROKEN seq=$seq atByte=$at")
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
                log.emit("RECONNECTING in 3s")
                delay(3_000)
            }
        }

        totals.report(log::emit)
        log.emit("DONE attempts=$attempt log=${logPath()}")
        statusLine = "done — ${totals.attempts} migration attempt(s), ${totals.succeeded} succeeded"
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
    var attempts = 0
        private set
    var succeeded = 0
        private set
    private var open = false
    private var openedAt = 0.0
    private val leaves = mutableMapOf<String, Int>()
    private var unanswered = 0
    private var probedAfterUnanswered = 0
    private var noSpareAfterUnanswered = 0

    fun onPath(state: QuicPathState) {
        when (state) {
            QuicPathState.Original -> Unit
            is QuicPathState.Probing -> {
                openAttempt()
                if (unanswered > 0) probedAfterUnanswered++
            }
            // Intermediate: the probe was answered, but the active path has not switched yet.
            is QuicPathState.Validated -> Unit
            is QuicPathState.Migrated -> {
                openAttempt()
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
        if (open) return
        open = true
        attempts++
        openedAt = NSDate().timeIntervalSince1970
    }

    private fun close(leaf: String) {
        leaves[leaf] = (leaves[leaf] ?: 0) + 1
        val took = ((NSDate().timeIntervalSince1970 - openedAt) * 1000).toLong()
        emit("MIGRATION-ATTEMPT n=$attempts outcome=$leaf tookMs=$took")
        open = false
    }

    /** The one line an operator mid-walk can act on: has it moved, and has the new path carried traffic. */
    fun oneLine(path: String): String = "$succeeded migration(s) · path=${path.substringBefore('(')} · unanswered probes=$unanswered"

    fun report(tag: String) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit("MIGRATION-LEDGER $tag attempts=$attempts succeeded=$succeeded outcomes=[$breakdown]")
        emit("447-VERDICT $tag ${verdict()}")
    }

    private fun verdict(): String =
        when {
            unanswered == 0 ->
                "INCONCLUSIVE — no probe went unanswered on this connection, so it exercises #445 only " +
                    "and says nothing about #447 (attempts=$attempts)"
            noSpareAfterUnanswered > 0 ->
                "REGRESSION — $unanswered unanswered probe(s), then $noSpareAfterUnanswered later " +
                    "attempt(s) answered NoSpareConnectionId: the pool did not come back (#447 alive)"
            probedAfterUnanswered > 0 || succeeded > 0 ->
                "PASS — $unanswered unanswered probe(s), and the connection still armed " +
                    "$probedAfterUnanswered later probe(s) with $succeeded migration(s) succeeding: " +
                    "the pool recovered in the field"
            else ->
                "INCONCLUSIVE — $unanswered unanswered probe(s) but no migration was attempted " +
                    "afterwards, so pool recovery was never put to the question"
        }

    fun fold(into: MigrationTotals) {
        into.connections++
        into.attempts += attempts
        into.succeeded += succeeded
        into.unanswered += unanswered
        into.probedAfterUnanswered += probedAfterUnanswered
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
    var noSpareAfterUnanswered = 0
    val leaves = mutableMapOf<String, Int>()

    fun absorb(ledger: MigrationLedger) = ledger.fold(this)

    fun report(emit: (String) -> Unit) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit(
            "MIGRATION-TOTALS connections=$connections attempts=$attempts succeeded=$succeeded " +
                "unansweredProbes=$unanswered probedAfterUnanswered=$probedAfterUnanswered " +
                "noSpareAfterUnanswered=$noSpareAfterUnanswered outcomes=[$breakdown]",
        )
        emit(
            "447-VERDICT walk " +
                when {
                    unanswered == 0 ->
                        "INCONCLUSIVE — not one probe went unanswered across $connections connection(s); " +
                            "this walk validates #445 only"
                    noSpareAfterUnanswered > 0 ->
                        "REGRESSION — NoSpareConnectionId answered $noSpareAfterUnanswered time(s) after " +
                            "an unanswered probe (#447 alive in the field)"
                    // A run-wide PASS requires that some connection which LOST a probe went on to
                    // arm another one — see the Android probe's ledger for the walk that proved the
                    // absence of NoSpareConnectionId is not enough: a reconnect negotiates a fresh
                    // CID pool, so its successes say nothing about the connection that lost one.
                    probedAfterUnanswered > 0 ->
                        "PASS — $unanswered unanswered probe(s), and a connection that lost one still " +
                            "armed $probedAfterUnanswered later probe(s) with no NoSpareConnectionId: " +
                            "the pool came back in the field"
                    else ->
                        "INCONCLUSIVE — $unanswered unanswered probe(s), but no connection that lost one " +
                            "ever attempted another migration, so pool recovery was never put to the " +
                            "question (a later connection's successes prove nothing: fresh pool)"
                },
        )
    }
}
