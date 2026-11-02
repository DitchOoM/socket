@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.testkit.walk.BuildRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Standalone QUIC echo server for Android instrumented tests + the
 * test-harness Docker container (test-harness/quic-echo/Dockerfile).
 *
 * Usage: QuicEchoTestServerKt <cert.crt> <cert.key> [port]
 *
 * Binds on the given port (**default 0 — an OS-assigned free port**), echoes
 * data back on each stream. Prints "READY port=<port>" with the *bound* port
 * to stdout when accepting connections. Runs until killed (SIGTERM/SIGINT).
 *
 * Callers must parse the port off the READY line rather than assume one: the
 * two-arg form no longer implies 4433. A fixed port is a machine-wide
 * singleton, so a server that outlives its run silently wedges the next one —
 * which is exactly what happened before this defaulted to 0.
 *
 * The optional third arg lets the harness container pin `14433` deliberately,
 * because docker publishes a fixed port and the compose file is the one place
 * where a constant is the contract.
 *
 * Before READY it prints a START line naming the commit the jar was built from and what the server
 * records (`qlog=off`, or the `QUIC_QLOG_DIR` directory and its budget), and writes the same line into
 * that directory's notes, so a pulled capture names the build that wrote it and a server that is
 * recording nothing says so in its first line.
 */
fun main(args: Array<String>) {
    require(args.size == 2 || args.size == 3) { "Usage: QuicEchoTestServer <cert.crt> <cert.key> [port]" }
    val certPath = args[0]
    val keyPath = args[1]

    // 0 = let the OS assign a free port. "The port is taken" is not a network condition worth
    // modelling — it is an artifact of choosing a constant, and a pinned port wedges the next run
    // whenever a previous server outlives it. The bound port is read back below and printed, so the
    // caller discovers it rather than assuming it. Defaults to 0 for the same reason the shared test
    // suites bind `withQuicServer(port = 0, …)`: never identify a socket by a bare constant.
    val requestedPort = if (args.size == 3) args[2].toInt() else 0
    val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            // Advertise RFC 9221 DATAGRAM support so datagram-enabled clients (incl.
            // the Apple group path, issue #109) can round-trip. Stream-only clients
            // are unaffected — this only advertises the transport parameter.
            datagrams = DatagramOptions(),
        )
    val tlsConfig = QuicTlsConfig(certChainPath = certPath, privKeyPath = keyPath)

    println(startLine(requestedPort))
    System.out.flush()

    runBlocking(Dispatchers.IO) {
        withQuicServer(port = requestedPort, tlsConfig = tlsConfig, quicOptions = quicOptions) {
            // `port` here is the scope's *bound* port, not what we asked for — with requestedPort = 0
            // the OS picks, and this is the only place the choice becomes knowable. The caller parses
            // it off this line, so the two sides never share a hardcoded constant.
            println("READY port=$port")
            System.out.flush()

            // Accept connections — each runs in its own coroutine scope
            connections { echoConnection(quicOptions) }
        }
    }
}

/**
 * The server's START line: the commit it was built from and what it records. When it records qlog,
 * the line also goes into the qlog directory's notes, so the capture itself names the build that
 * wrote it.
 */
internal fun startLine(requestedPort: Int): String {
    val revision = serverBuildRevision()
    return when (val dir = qlogDir()) {
        null -> "START ${revision.line} port=$requestedPort qlog=off"
        else -> {
            val directory = QlogEnvironment.directory(dir)
            "START ${revision.line} port=$requestedPort qlog=${directory.path} ${directory.budget.line}".also {
                QlogEnvironment.appendNote(
                    dir,
                    it,
                )
            }
        }
    }
}

/** The commit stamped into this jar by `generateBuildStamp`; a run from the build's classes carries none. */
private fun serverBuildRevision(): BuildRevision =
    QlogEnvironment::class.java
        .getResourceAsStream("/build-revision.txt")
        ?.use { BuildRevision.parse(it.readBytes().decodeToString()) }
        ?: BuildRevision.Unknown(BuildRevision.UnknownRevision.NotPackaged)
