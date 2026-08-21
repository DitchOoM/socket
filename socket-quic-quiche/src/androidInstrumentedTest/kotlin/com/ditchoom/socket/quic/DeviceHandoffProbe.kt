package com.ditchoom.socket.quic

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

        log.writeText("")
        emit("START device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} target=$host:$port minutes=$minutes readTimeoutMs=$readTimeoutMs")

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

        runBlocking(Dispatchers.IO) {
            while (System.currentTimeMillis() < deadline) {
                attempt++
                emit("CONNECT-ATTEMPT n=$attempt")
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
                        launch { pathState.collect { emit("PATH $it") } }

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
                                    if (!intact && !integrityBroken) {
                                        integrityBroken = true
                                        // The whole point of the run. Capture both sides at the divergence.
                                        val at = recvAll.indices.firstOrNull { it >= sentAll.length || sentAll[it] != recvAll[it] } ?: 0
                                        emit(
                                            "STREAM-INTEGRITY-BROKEN seq=$seq atByte=$at " +
                                                "sent=[${sentAll.substring(maxOf(0, at - 24), minOf(sentAll.length, at + 24))}] " +
                                                "recv=[${recvAll.substring(maxOf(0, at - 24), minOf(recvAll.length, at + 24))}]",
                                        )
                                    }
                                } else {
                                    emit("ECHO-NO-DATA seq=$seq after=${rtt}ms result=$resp")
                                }
                            } catch (e: Throwable) {
                                // The interesting case. Record and keep going — the connection may still
                                // be alive and migrating underneath us.
                                emit(
                                    "ECHO-FAIL seq=$seq after=${System.currentTimeMillis() - sentAt}ms " +
                                        "err=${e::class.simpleName} msg=${e.message}",
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
                                    // device log is all we get from a real handoff (#437).
                                    emit(
                                        "CONNECTION-DEAD seq=$seq reason=${e.closeReason.describe()} " +
                                            "— leaving scope to reconnect",
                                    )
                                    return@withQuicConnection
                                }
                            }
                            delay(2_000)
                        }
                    }
                    emit("SCOPE-EXITED cleanly")
                } catch (e: Throwable) {
                    emit("CONNECTION-ENDED err=${e::class.simpleName} msg=${e.message}")
                }
                if (System.currentTimeMillis() < deadline) {
                    emit("RECONNECTING in 3s")
                    delay(3_000)
                }
            }
        }

        if (wakeLock.isHeld) wakeLock.release()
        emit("WAKELOCK released")
        emit("DONE attempts=$attempt log=${log.absolutePath}")
    }
}
