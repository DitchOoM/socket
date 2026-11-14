package com.ditchoom.socket.quic

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The `TESTCASE` names the server implements. Everything else answers [UNSUPPORTED]:
 *  - `retry`: quiche can send Retry, but this endpoint does not drive token validation yet.
 *  - `connectionmigration`: the runner's migration test is the server's preferred_address, which quiche
 *    does not advertise. The CLIENT is handed `transfer` there, so our client against a peer that
 *    advertises one is reported as a failure, which is accurate: it does not move to it.
 *  - `keyupdate`, `chacha20`, `ecn`, `v2`: quiche exposes no per-connection control for them.
 *  - `http3`: socket-http3 can serve it; wiring it in here is a follow-up.
 *
 * The client's tests and its `CLIENT_PARAMS` (`migrate=active`) are described at [runInteropClient].
 */
private val serverTests = setOf("handshake", "transfer", "multiconnect", "resumption", "zerortt")

/**
 * This library as a [quic-interop-runner](https://github.com/quic-interop/quic-interop-runner) endpoint,
 * so its client and server are tested against other QUIC stacks (quic-go, ngtcp2, quinn, msquic, …)
 * and not only against quiche itself. Packaged by `quicInteropJar` into test-harness/quic-interop.
 *
 * The runner's contract, which run_endpoint.sh passes through as environment:
 *  - `ROLE` is `client` or `server`; `TESTCASE` names the test. A test this endpoint does not implement
 *    exits **127**, which the runner reports as "unsupported" rather than as a failure.
 *  - The protocol is `hq-interop` (HTTP/0.9 over QUIC): a request is `GET /<path>\r\n` on a fresh
 *    bidirectional stream, sent with FIN; the response is the file's bytes, sent with FIN.
 *  - The server serves `/www` with the certificate in `/certs`; the client downloads every URL in
 *    `REQUESTS` (space separated) into `/downloads/<path>`.
 *  - Key logs: `QUIC_KEYLOG_DIR` is set by run_endpoint.sh, which concatenates the per-connection files
 *    into the runner's `SSLKEYLOGFILE` so it can decrypt the capture.
 */
fun main() {
    val role = System.getenv("ROLE")
    val test = System.getenv("TESTCASE").orEmpty()
    val code =
        when (role) {
            "server" -> if (test in serverTests) runServer(test) else UNSUPPORTED
            "client" -> runClient()
            else -> {
                System.err.println("ROLE must be client or server, was '$role'")
                1
            }
        }
    System.out.flush()
    exitProcess(code)
}

// ---- server -------------------------------------------------------------------------------------

private fun runServer(test: String): Int {
    val www = File(System.getenv("WWW") ?: "/www")
    val certs = File(System.getenv("CERTS") ?: "/certs")
    val port = System.getenv("PORT")?.toInt() ?: 443
    val options = interopBaseOptions().copy(enableEarlyData = test == "zerortt" || test == "resumption")
    val tls = QuicTlsConfig(certChainPath = certs.resolve("cert.pem").path, privKeyPath = certs.resolve("priv.key").path)
    runBlocking(Dispatchers.IO) {
        val serving =
            launch {
                withQuicServer(port = port, tlsConfig = tls, quicOptions = options) {
                    println("READY port=${this.port} test=$test")
                    System.out.flush()
                    connections {
                        coroutineScope {
                            streams().collect { stream -> launch { serveRequest(stream, www) } }
                        }
                    }
                }
            }
        // The runner stops the server with SIGTERM. Ending the server, not just the process, lets every
        // connection free its quiche state, which is when quiche writes out the rest of its qlog.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runBlocking { withTimeoutOrNull(SHUTDOWN_BUDGET) { serving.cancelAndJoin() } }
            },
        )
        serving.join()
    }
    return 0
}

/** Inside the runner's ten-second stop timeout. */
private val SHUTDOWN_BUDGET = 5.seconds

/** Read `GET /path\r\n` to the request's FIN, then send the file and FIN. */
private suspend fun QuicScope.serveRequest(
    stream: QuicByteStream,
    www: File,
) {
    try {
        // hq-interop GETs are replay-safe: serving a file twice changes nothing, so a request that
        // arrived as 0-RTT is answered at once rather than after the handshake is confirmed.
        stream.markReplaySafe()
        val request = StringBuilder()
        while (true) {
            when (val r = stream.read(30.seconds)) {
                is ReadResult.Data ->
                    try {
                        request.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
            if (request.contains('\n')) break
        }
        val path =
            request
                .lineSequence()
                .first()
                .trim()
                .removePrefix("GET")
                .trim()
                .trimStart('/')
        val file = www.resolve(path).canonicalFile
        if (!file.path.startsWith(www.canonicalPath) || !file.isFile) {
            stream.reset(0x10C) // H3_REQUEST_REJECTED's value; hq-interop has no status line to send
            return
        }
        sendFile(stream, file)
    } catch (e: Exception) {
        System.err.println("request failed: $e")
    } finally {
        stream.close()
    }
}

/** Send [file] and FIN the stream, the FIN riding the last chunk so a small file is one packet. */
private suspend fun QuicScope.sendFile(
    stream: QuicByteStream,
    file: File,
) {
    val chunk = 64 * 1024
    val buf = bufferFactory.allocate(chunk)
    try {
        var left = file.length()
        if (left == 0L) return stream.shutdownSend()
        file.inputStream().use { input ->
            val bytes = ByteArray(chunk)
            while (left > 0) {
                val n = input.read(bytes)
                if (n <= 0) error("${file.path} ended $left bytes early")
                left -= n
                buf.resetForWrite()
                buf.writeBytes(bytes, 0, n)
                buf.resetForRead()
                if (left > 0) stream.writeFully(buf, 60.seconds) else stream.writeFullyAndShutdownSend(buf, 60.seconds)
            }
        }
    } finally {
        buf.freeIfNeeded()
    }
}

// ---- client -------------------------------------------------------------------------------------

/** The shared client ([runInteropClient]) on the JVM. */
private object JvmInteropHost : InteropHost {
    override fun env(name: String): String = System.getenv(name).orEmpty()

    override fun create(path: String): InteropDownload {
        val target = File(path)
        target.parentFile?.mkdirs()
        val out = FileOutputStream(target)
        return object : InteropDownload {
            override fun write(bytes: ByteArray) = out.write(bytes)

            override fun close() = out.close()
        }
    }

    override fun reportError(line: String) = System.err.println(line)

    override fun flushOutput() = System.out.flush()
}

private fun runClient(): Int {
    // Load quiche before the network is up, so the first Initial leaves as soon as it is: the runner's
    // rebinding tests move the client's port one second in, and a handshake that straddles the move cannot
    // be validated on the new path.
    loadQuicheApi()
    awaitNetwork(System.getenv("WAIT_FOR").orEmpty())
    return runBlocking(Dispatchers.IO) { runInteropClient(JvmInteropHost) }
}

/** Polls `host:port` until it accepts a TCP connection: the simulator is up. Returns at once without one. */
private fun awaitNetwork(hostPort: String) {
    if (hostPort.isEmpty()) return
    val host = hostPort.substringBeforeLast(':')
    val port = hostPort.substringAfterLast(':').toInt()
    val deadline = TimeSource.Monotonic.markNow() + NETWORK_WAIT
    while (deadline.hasNotPassedNow()) {
        val reached =
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), NETWORK_POLL.inWholeMilliseconds.toInt()) }
                true
            } catch (_: IOException) {
                false
            }
        if (reached) return
        Thread.sleep(NETWORK_POLL.inWholeMilliseconds)
    }
}

private val NETWORK_WAIT = 30.seconds
private val NETWORK_POLL = 10.milliseconds
