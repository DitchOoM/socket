package com.ditchoom.socket.quic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
 *   -e probeHost 100.110.209.112 -e probePort 14433 -e probeMinutes 12 \
 *   com.ditchoom.socket.quic.quiche.test/androidx.test.runner.AndroidJUnitRunner
 * ```
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
        val host = arg("probeHost", "")
        assumeTrue("hand-driven probe — pass -e probeHost <ip> to run it (see class KDoc)", host.isNotEmpty())
        val port = arg("probePort", "14433").toInt()
        val minutes = arg("probeMinutes", "12").toInt()
        // Read deadline, injectable so a run can be tuned to ARM the #393 salvage path rather than
        // merely hope for it. The field default (8s) sees ~1 timeout per 15 minutes, so a short probe
        // records zero and proves nothing about the cancellation edge. Setting this just under the
        // cellular RTT (~200ms median, ~500ms post-migration spikes) makes timeouts routine.
        val readTimeoutMs = arg("probeReadTimeoutMs", "8000").toLong()
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

        fun emit(line: String) {
            val t = System.currentTimeMillis() - started
            val rendered = "t=${t}ms $line"
            log.appendText(rendered + "\n")
            // logcat too, so a tethered run is watchable live: adb logcat -s QuicHandoffProbe
            android.util.Log.i("QuicHandoffProbe", rendered)
        }

        // Frame-level evidence, off by default. The driver's qlog seam reads the `quic.qlog.dir`
        // system property (or QUIC_QLOG_DIR, which an `am instrument` run cannot set) — so a device is
        // the one place a real handoff can be recorded and, until that property existed, the one place
        // qlog could not be turned on. Writes one .sqlog per connection into the app's external files
        // dir, where `adb pull` can reach it. Off by default because at a 100ms cadence it is megabytes
        // per minute and it is an instrument, not a feature.
        val qlog = arg("probeQlog", "").isNotEmpty()
        val qlogDir = File(dir, "qlog")
        if (qlog) {
            qlogDir.mkdirs()
            qlogDir.listFiles()?.forEach { it.delete() }
            System.setProperty("quic.qlog.dir", qlogDir.absolutePath)
        } else {
            System.clearProperty("quic.qlog.dir")
        }

        log.writeText("")
        emit(
            "START device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} target=$host:$port " +
                "minutes=$minutes readTimeoutMs=$readTimeoutMs echoIntervalMs=$echoIntervalMs " +
                "qlog=${if (qlog) qlogDir.absolutePath else "off"}",
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

        val deadline = started + minutes * 60_000L
        var attempt = 0

        // A coroutine `delay` does NOT wake the application processor from suspend, so both the echo
        // loop and the QUIC keepalive stall the moment the screen locks — i.e. exactly when this probe
        // is in a pocket recording the walk it exists to record. A Doze whitelist does not help: that
        // governs network policy, not CPU suspend. Acquired WITH a timeout so a probe that dies can
        // never pin the AP awake; that timeout is also why no finally block is needed.
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socket:quic-handoff-probe")
        wakeLock.acquire(minutes * 60_000L + 120_000L)
        emit("WAKELOCK acquired held=${wakeLock.isHeld} timeoutMs=${minutes * 60_000L + 120_000L}")

        // Live status in the shade, so the walk can be driven by what the connection actually did
        // rather than by a stopwatch. See [ProbeStatus].
        val status = ProbeStatus(ctx, ::emit)

        // Per-handoff migration capture. Without it a green run is over-read: #447 only bites when a
        // probe goes UNANSWERED, and on a healthy handoff every probe is answered on the first try —
        // so a run of thirty clean migrations proves #445 and says nothing whatever about #447, while
        // reading exactly as if it had validated both. See [MigrationLedger].
        val totals = MigrationTotals()

        runBlocking(Dispatchers.IO) {
            while (System.currentTimeMillis() < deadline) {
                attempt++
                emit("CONNECT-ATTEMPT n=$attempt")
                if (attempt > 1) status.onEnded("reconnecting (attempt $attempt)")
                // Per CONNECTION, not per run: a reconnect negotiates a brand-new CID pool, so a pool
                // exhausted on the previous connection says nothing about this one.
                val ledger = MigrationLedger(::emit)
                try {
                    // NOTE: this `timeout` bounds the ENTIRE scope block, not just the connect —
                    // measured, the first run of this probe tore the connection down every 15s with
                    // `TimeoutCancellationException: Timed out waiting for 15000 ms` while echoes were
                    // flowing fine. So it has to cover the whole walk, not the handshake.
                    withQuicConnection(host, port, options, timeout = (minutes + 2).minutes) {
                        emit("CONNECTED session=${identity.session} wire=${identity.wire} alpn=$negotiatedAlpn")

                        val stream = openStream()
                        var seq = 0
                        var lastWire = identity.wire

                        // Stream-integrity ledger (#393).
                        //
                        // With a short read deadline a timeout is EXPECTED, and a working salvage path
                        // hands those bytes to the NEXT read — so the old per-read `echoed == payload`
                        // test would report a mismatch on correct behaviour. Small payloads can also
                        // coalesce, so one read may carry two echoes.
                        //
                        // The invariant that survives both: everything received must be an exact,
                        // in-order PREFIX of everything sent. Late delivery keeps that true; bytes
                        // destroyed by a timed-out read (the #393 defect) break it permanently, because
                        // the stream then resumes past the hole.
                        val sentAll = StringBuilder()
                        val recvAll = StringBuilder()
                        var integrityBroken = false

                        // A DEDICATED collector, replacing a `pathState.value` read inside the 2s echo
                        // loop below. Phase 4 bounds an unanswered path probe at ~3s (RFC 9000 §8.2.4),
                        // so a whole Probing -> Failed -> Probing sequence can fall between two samples
                        // of a 2s poll and go UNCOUNTED — which would make a working fix look broken,
                        // because the acceptance criterion here is "N handoffs produce N migration
                        // attempts". StateFlow still conflates, but now at collector speed.
                        launch {
                            pathState.collect {
                                emit("PATH $it")
                                status.onPath(it.toString())
                                ledger.onPath(it)
                            }
                        }

                        while (System.currentTimeMillis() < deadline) {
                            // A wire CID that rotates while the session id holds is exactly what a
                            // successful migration looks like.
                            if (identity.wire != lastWire) {
                                emit("WIRE-CID-ROTATED session=${identity.session} wire=${identity.wire}")
                                lastWire = identity.wire
                            }

                            seq++
                            val sentAt = System.currentTimeMillis()
                            // Delimited so payload boundaries stay visible in a coalesced read.
                            val payload = "probe-$seq;"
                            try {
                                val out = BufferFactory.Default.allocate(payload.length)
                                out.writeString(payload, Charset.UTF8)
                                out.resetForRead()
                                stream.write(out, 5.seconds)
                                sentAll.append(payload)
                                val resp = stream.read(readTimeoutMs.milliseconds)
                                val rtt = System.currentTimeMillis() - sentAt
                                if (resp is ReadResult.Data) {
                                    val echoed = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
                                    recvAll.append(echoed)
                                    val intact = sentAll.startsWith(recvAll)
                                    val pending = sentAll.length - recvAll.length
                                    emit("ECHO-OK seq=$seq rtt=${rtt}ms got=${echoed.length}B intact=$intact pending=${pending}B")
                                    status.onEcho(gotData = true, intactNow = intact, pendingBytes = pending)
                                    if (!intact && !integrityBroken) {
                                        integrityBroken = true
                                        // The whole point of the run. Capture both sides at the divergence.
                                        val at = recvAll.indices.firstOrNull { it >= sentAll.length || sentAll[it] != recvAll[it] } ?: 0
                                        status.onBroken(at)
                                        emit(
                                            "STREAM-INTEGRITY-BROKEN seq=$seq atByte=$at " +
                                                "sent=[${sentAll.substring(maxOf(0, at - 24), minOf(sentAll.length, at + 24))}] " +
                                                "recv=[${recvAll.substring(maxOf(0, at - 24), minOf(recvAll.length, at + 24))}]",
                                        )
                                    }
                                } else {
                                    emit("ECHO-NO-DATA seq=$seq after=${rtt}ms result=$resp")
                                    status.onEcho(
                                        gotData = false,
                                        intactNow = sentAll.startsWith(recvAll),
                                        pendingBytes = sentAll.length - recvAll.length,
                                    )
                                }
                            } catch (e: Throwable) {
                                // The interesting case. Record and keep going — the connection may still
                                // be alive and migrating underneath us.
                                emit(
                                    "ECHO-FAIL seq=$seq after=${System.currentTimeMillis() - sentAt}ms " +
                                        "err=${e::class.simpleName} msg=${e.message}",
                                )
                                // A read that times out THROWS — it does not return a non-Data result — so
                                // this branch, not ECHO-NO-DATA, is where a deadline lands. Wiring the
                                // counter only to the other branch left the shade frozen on the last good
                                // state while every read was failing: measured, 30s of
                                // `timeouts=0 intact=yes` against a server that had been dead the whole
                                // time. With a sub-RTT deadline this is the COMMON case during a handoff,
                                // which is exactly when the operator is reading it.
                                status.onEcho(
                                    gotData = false,
                                    intactNow = sentAll.startsWith(recvAll),
                                    pendingBytes = sentAll.length - recvAll.length,
                                )
                                // ...unless it is DEAD, in which case "keep going" means spinning this
                                // loop against a closed connection for the rest of the run. Measured: a
                                // connection that idle-timed out at t=178s produced an unbroken wall of
                                // ECHO-FAIL to the deadline, so an unattended multi-hour recording would
                                // capture one death and then nothing. Leave the scope instead and let the
                                // outer loop reconnect — a reconnect is itself data (it is precisely what
                                // distinguishes "migrated" from "had to start over").
                                if (e is QuicCloseException) {
                                    // The typed reason, side included: "we sent a frame the peer
                                    // rejected" and "the peer sent us one" are opposite bugs, and a
                                    // device log is all we get from a real handoff (#437). It goes in
                                    // the shade too — that is where a walk reads its state.
                                    val why = e.closeReason.describe()
                                    status.onEnded("connection dead ($why) — reconnecting")
                                    emit("CONNECTION-DEAD seq=$seq reason=$why — leaving scope to reconnect")
                                    return@withQuicConnection
                                }
                            }
                            delay(echoIntervalMs)
                        }
                    }
                    emit("SCOPE-EXITED cleanly")
                } catch (e: Throwable) {
                    emit("CONNECTION-ENDED err=${e::class.simpleName} msg=${e.message}")
                }
                ledger.report("connection=$attempt")
                totals.absorb(ledger)
                if (System.currentTimeMillis() < deadline) {
                    emit("RECONNECTING in 3s")
                    delay(3_000)
                }
            }
        }

        if (wakeLock.isHeld) wakeLock.release()
        emit("WAKELOCK released")
        totals.report(::emit)
        emit("DONE attempts=$attempt log=${log.absolutePath}")
    }
}

/**
 * A live status notification for the walk — because the operator cannot watch logcat while walking,
 * and the one question a walk actually raises is "has it moved yet, and has the new path carried
 * enough traffic that I can go back?".
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
) {
    private val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private var usable = false
    private var path = "connecting"
    private var migrations = 0
    private var echoes = 0
    private var sinceMove = 0
    private var timeouts = 0
    private var intact = true
    private var pending = 0
    private var broken: String? = null

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

    @Synchronized
    fun onPath(state: String) {
        path = state.substringBefore('(').substringAfterLast('.')
        if (state.contains("Migrated")) {
            migrations++
            sinceMove = 0
        }
        post()
    }

    @Synchronized
    fun onEcho(
        gotData: Boolean,
        intactNow: Boolean,
        pendingBytes: Int,
    ) {
        echoes++
        sinceMove++
        if (!gotData) timeouts++
        intact = intactNow
        pending = pendingBytes
        post()
    }

    @Synchronized
    fun onBroken(atByte: Int) {
        broken = "byte $atByte"
        intact = false
        post()
    }

    @Synchronized
    fun onEnded(reason: String) {
        path = reason
        post()
    }

    private fun post() {
        if (!usable) return
        val title =
            when {
                broken != null -> "⚠ STREAM INTEGRITY BROKEN at $broken"
                migrations == 0 -> "QUIC probe · no migration yet"
                else -> "QUIC probe · $migrations migration(s) · $sinceMove echoes since"
            }
        val text =
            "path=$path · echoes=$echoes · timeouts=$timeouts · " +
                "intact=${if (intact) "yes" else "NO"} · pending=${pending}B"
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
    private var attempts = 0
    private var succeeded = 0
    private var openedAt = 0L
    private var open = false
    private val leaves = LinkedHashMap<String, Int>()

    /** Attempts resolved by an unanswered PATH_CHALLENGE — the precondition #447 needs to be visible. */
    private var unanswered = 0

    /** …and what happened on the attempts that came AFTER the first one. This is the verdict. */
    private var probedAfterUnanswered = 0
    private var noSpareAfterUnanswered = 0

    @Synchronized
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
        if (open) return
        open = true
        attempts++
        openedAt = System.currentTimeMillis()
    }

    private fun close(leaf: String) {
        leaves[leaf] = (leaves[leaf] ?: 0) + 1
        emit("MIGRATION-ATTEMPT n=$attempts outcome=$leaf tookMs=${System.currentTimeMillis() - openedAt}")
        open = false
    }

    @Synchronized
    fun report(tag: String) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit("MIGRATION-LEDGER $tag attempts=$attempts succeeded=$succeeded outcomes=[$breakdown]")
        emit("447-VERDICT $tag ${verdict()}")
    }

    @Synchronized
    fun verdict(): String =
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

    @Synchronized
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

/** Run-wide roll-up of every connection's [MigrationLedger] — the line the operator reads at the end. */
private class MigrationTotals {
    var connections = 0
    var attempts = 0
    var succeeded = 0
    var unanswered = 0
    var probedAfterUnanswered = 0
    var noSpareAfterUnanswered = 0
    val leaves = LinkedHashMap<String, Int>()

    fun absorb(ledger: MigrationLedger) = ledger.fold(this)

    fun report(emit: (String) -> Unit) {
        val breakdown = leaves.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "none" }
        emit(
            "MIGRATION-TOTALS connections=$connections attempts=$attempts succeeded=$succeeded " +
                "unansweredProbes=$unanswered probedAfterUnanswered=$probedAfterUnanswered " +
                "noSpareAfterUnanswered=$noSpareAfterUnanswered outcomes=[$breakdown]",
        )
        emit(
            "447-VERDICT run " +
                when {
                    unanswered == 0 ->
                        "INCONCLUSIVE — not one probe went unanswered across $connections connection(s); " +
                            "this run validates #445 only"
                    noSpareAfterUnanswered > 0 ->
                        "REGRESSION — NoSpareConnectionId answered $noSpareAfterUnanswered time(s) after " +
                            "an unanswered probe (#447 alive in the field)"
                    else ->
                        "PASS — $unanswered unanswered probe(s) and no NoSpareConnectionId afterwards; " +
                            "the pool came back every time"
                },
        )
    }
}
