package com.ditchoom.socket.quic

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Ports of the host-side harness servers, as told to us by whoever started them.
 *
 * The servers bind port 0 and the OS assigns — so the number cannot be known ahead of time by either
 * side, and cannot be a constant here. `androidQuicIntegrationTest` reads the bound port off each
 * server's `READY port=<n>` line and passes it down as an instrumentation argument; this is where the
 * device picks it up.
 *
 * ## Why not just pin a port
 * A fixed port is a machine-wide singleton. A server that outlives its run holds it, and the next run
 * fails with "address in use" — a failure mode that exists only because a constant was chosen. It also
 * forbids two suites running at once. The shared test suites already bind `withQuicServer(port = 0, …)`
 * for the same reason; this brings the Android harness in line.
 *
 * ## Fallbacks are the docker contract, not a guess
 * When an argument is absent the caller is the **docker** harness path (`test-harness/docker-compose.yml`),
 * where the published port genuinely *is* a fixed contract written in the compose file — that is the one
 * place a constant is correct. So the fallbacks match `harness.env`, and a missing argument means
 * "docker harness", never "we forgot".
 */
internal object HarnessPorts {
    /** Published `quic-echo` port in `test-harness/docker-compose.yml`; used when no argument was passed. */
    private const val DOCKER_QUIC_ECHO_PORT = 14433

    /** Legacy fixed control port, kept only as the no-argument fallback. */
    private const val DEFAULT_NET_CTRL_PORT = 9998

    val quicEcho: Int get() = argOrDefault("quicEchoPort", DOCKER_QUIC_ECHO_PORT)

    val netCtrl: Int get() = argOrDefault("netCtrlPort", DEFAULT_NET_CTRL_PORT)

    private fun argOrDefault(
        name: String,
        fallback: Int,
    ): Int =
        runCatching { InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() }
            .getOrNull() ?: fallback
}
