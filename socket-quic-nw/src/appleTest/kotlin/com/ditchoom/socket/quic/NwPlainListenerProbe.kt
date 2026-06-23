@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.nwhelpers.nw_helper_create_quic_listener
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_identity_from_p12
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_cancel
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_get_port
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_set_new_connection_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_set_state_handler
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_listener_start
import com.ditchoom.socket.quic.nwhelpers.nw_helper_quic_start
import kotlinx.cinterop.toKString
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv
import platform.posix.usleep
import kotlin.test.Test

/**
 * DIAGNOSTIC PROBE (anti-amplification interop hunt) — **not a unit test**, gated by the
 * `NW_PLAIN_PROBE` environment variable so an ordinary `macosArm64Test` skips it.
 *
 * Stands up a **plain, non-group** Network.framework QUIC listener (one `nw_connection` per QUIC
 * stream — the single-stream model, NOT the multiplex connection-group the production
 * [withQuicServer] uses) presenting the long-lived **RSA** `cert.p12`. A raw quiche JVM client
 * (`QuichePlainProbeClient`) dials it over localhost.
 *
 * The question it answers: does a *plain* NW listener under-credit the client's Initial for
 * RFC 9000 §8.1 anti-amplification the same way the connection-group server does (proven: an RSA
 * cert flight deadlocks a quiche client, a tiny EC one squeaks through)? If the plain listener also
 * deadlocks, the bug is libquic-wide (the listener→connection handoff), not group-specific — so the
 * only server-side fix is a small cert flight regardless of the server model. The client's success
 * or `IdleTimeout` IS the signal; this side just completes (or fails to complete) the handshake.
 *
 * Binds an ephemeral port, writes `port=<n>` to `NW_PLAIN_CONFIG` (its existence = readiness), and
 * serves until `NW_PLAIN_STOP` appears or [MAX_LIFETIME_US] elapses.
 */
class NwPlainListenerProbe {
    @Test
    fun runUntilStopped() {
        if (envOrNull("NW_PLAIN_PROBE") != "true") return // gated: skip on ordinary runs

        val configFile = envOrNull("NW_PLAIN_CONFIG") ?: error("NW_PLAIN_CONFIG not set")
        val stopFile = envOrNull("NW_PLAIN_STOP") ?: error("NW_PLAIN_STOP not set")

        val p12Path = appleTestCertPath("cert.p12") // RSA-2048 leaf — the flight size that deadlocks the group server
        val p12Data = NSData.create(contentsOfFile = p12Path) ?: error("cannot read $p12Path")
        val identity =
            nw_helper_quic_identity_from_p12(p12Data, APPLE_TEST_P12_PASSWORD)
                ?: error("PKCS#12 import failed for $p12Path")

        val listener =
            nw_helper_create_quic_listener(
                null,
                0u, // ephemeral
                listOf("hq-interop"),
                identity,
                30, // idleTimeout seconds
                0,
                0u, // no datagrams
            ) ?: error("failed to create plain QUIC listener")

        // Plain (non-group) accept: each incoming QUIC stream arrives as one nw_connection. Start it so
        // the handshake proceeds and the connection stays live; the diagnostic is purely whether the
        // client's handshake completes, so no echo is needed here.
        nw_helper_quic_listener_set_new_connection_handler(listener) { conn ->
            nw_helper_quic_start(conn)
            println("NW_PLAIN_CONNECTION_DELIVERED") // only prints if the handshake got far enough to deliver a stream
        }

        var ready = false
        var failed: String? = null
        nw_helper_quic_listener_set_state_handler(listener) { state, _, code, desc ->
            when (state) {
                2 -> ready = true
                3 -> failed = "listener failed code=$code ${desc ?: ""}"
                4 -> failed = "listener cancelled"
                else -> {}
            }
        }
        nw_helper_quic_listener_start(listener)

        // Spin until the listener is ready (port assigned) — callbacks land on NW's serial queue.
        var waited = 0L
        while (!ready && failed == null && waited < READY_TIMEOUT_US) {
            usleep(POLL_US)
            waited += POLL_US.toLong()
        }
        failed?.let { error(it) }
        check(ready) { "listener never became ready" }

        val port = nw_helper_quic_listener_get_port(listener).toInt()
        println("NW_PLAIN_READY port=$port")
        writeTextFile(configFile, "port=$port\n")

        var lived = 0L
        while (access(stopFile, F_OK) != 0 && lived < MAX_LIFETIME_US) {
            usleep(POLL_US)
            lived += POLL_US.toLong()
        }
        println("NW_PLAIN_STOPPING (stop=${access(stopFile, F_OK) == 0}, livedUs=$lived)")
        nw_helper_quic_listener_cancel(listener)
    }

    private fun envOrNull(name: String): String? = getenv(name)?.toKString()

    private fun writeTextFile(
        path: String,
        text: String,
    ) {
        val fp = fopen(path, "w") ?: error("cannot open $path for write")
        try {
            fputs(text, fp)
        } finally {
            fclose(fp)
        }
    }

    private companion object {
        const val POLL_US: UInt = 100_000u // 100ms
        const val READY_TIMEOUT_US = 10_000_000L // 10s
        const val MAX_LIFETIME_US = 120_000_000L // 2min
    }
}
