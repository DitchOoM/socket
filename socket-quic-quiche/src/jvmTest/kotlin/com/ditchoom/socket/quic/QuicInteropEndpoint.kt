package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
private const val HQ = "hq-interop"
private const val UNSUPPORTED = 127

/**
 * withQuicConnection's timeout bounds the handshake AND the block. The runner's own limit is what should
 * stop a slow test (a 10 MB transfer at 10 Mbit/s under loss outlives the 15 s default), so this is generous.
 */
private val BLOCK_TIMEOUT = 5.minutes

/**
 * The `TESTCASE` names each role implements. These are the names the runner hands the ENDPOINT, which
 * differ from its test names: `longrtt` arrives as `handshake`; `multiplexing`, the loss, corruption,
 * blackhole, rebind, ipv6 and goodput tests as `transfer`; `handshakeloss`/`handshakecorruption` as
 * `multiconnect`. Everything else answers 127:
 *  - `retry` (server): quiche can send Retry, but this endpoint does not drive token validation yet.
 *    As a client it is supported; quiche follows a Retry on its own.
 *  - `connectionmigration` (server): the runner's migration test is the server's preferred_address,
 *    which quiche does not advertise. Note the CLIENT is handed `transfer` there, so our client against
 *    a peer that advertises one is reported as a failure, which is accurate: it does not move to it.
 *  - `keyupdate`, `chacha20`, `ecn`, `v2`: quiche exposes no per-connection control for them.
 *  - `http3`: socket-http3 can serve it; wiring it in here is a follow-up.
 *
 * The runner has no test in which the CLIENT moves its own path, so this endpoint adds one through
 * `CLIENT_PARAMS`, which the runner's compose file passes to the client container from the environment the
 * runner runs in: with `migrate=active` the client's `transfer` migrates while it downloads (see
 * [ClientMode.ActiveMigration]). The runner still judges the transfer itself.
 */
private val clientTests = setOf("handshake", "transfer", "multiconnect", "retry", "resumption", "zerortt")
private val serverTests = setOf("handshake", "transfer", "multiconnect", "resumption", "zerortt")

fun main() {
    val role = System.getenv("ROLE")
    val test = System.getenv("TESTCASE").orEmpty()
    val code =
        when (role) {
            "server" -> if (test in serverTests) runServer(test) else UNSUPPORTED
            "client" -> if (test in clientTests) runClient(test) else UNSUPPORTED
            else -> {
                System.err.println("ROLE must be client or server, was '$role'")
                1
            }
        }
    System.out.flush()
    exitProcess(code)
}

private fun baseOptions() =
    QuicOptions(
        alpnProtocols = listOf(HQ),
        verifyPeer = false,
        idleTimeout = 30.seconds,
        // The interop transfers are up to 10 MB per file and thousands of streams per connection.
        flowControl =
            FlowControl(
                initialMaxData = 64L * 1024 * 1024,
                initialMaxStreamDataBidiLocal = 16L * 1024 * 1024,
                initialMaxStreamDataBidiRemote = 16L * 1024 * 1024,
                initialMaxStreamsBidi = 1000,
            ),
    )

// ---- server -------------------------------------------------------------------------------------

private fun runServer(test: String): Int {
    val www = File(System.getenv("WWW") ?: "/www")
    val certs = File(System.getenv("CERTS") ?: "/certs")
    val port = System.getenv("PORT")?.toInt() ?: 443
    val options = baseOptions().copy(enableEarlyData = test == "zerortt" || test == "resumption")
    val tls = QuicTlsConfig(certChainPath = certs.resolve("cert.pem").path, privKeyPath = certs.resolve("priv.key").path)
    runBlocking(Dispatchers.IO) {
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
    return 0
}

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

/** What the client does beyond what `TESTCASE` asks, from `CLIENT_PARAMS`. */
private sealed interface ClientMode {
    data object Plain : ClientMode

    /**
     * `transfer` with [MOVES] client-initiated migrations (RFC 9000 §9), one every [MOVE_EVERY_BYTES]
     * received, each onto a fresh local port while the responses are still arriving. Every move's typed
     * result is printed as a `MIGRATION` line and the connection's totals as one `MIGRATION-SUMMARY` line,
     * so a peer that refuses or limits migration reads differently from a transfer that broke: the first is
     * a result, the second a failed download.
     */
    data object ActiveMigration : ClientMode
}

private const val MOVES = 6
private const val MOVE_EVERY_BYTES = 1_500_000L

/**
 * How long one move may take, retries included. Path validation gives up after three probe timeouts
 * (RFC 9000 §8.2.4), well under this, so a move that outlasts it is hung: it is reported with the driver's
 * state and the moves stop, while the download carries on.
 */
private val MOVE_BUDGET = 20.seconds

/** A [MigrationResult.Unmoved.Failed.NoSpareConnectionId] can clear once the peer issues an id; tries per move. */
private const val SPARE_ID_ATTEMPTS = 5
private val SPARE_ID_WAIT = 100.milliseconds

private fun clientMode(): ClientMode =
    if (System
            .getenv("CLIENT_PARAMS")
            .orEmpty()
            .split(' ')
            .contains("migrate=active")
    ) {
        ClientMode.ActiveMigration
    } else {
        ClientMode.Plain
    }

private fun runClient(test: String): Int {
    val urls =
        System
            .getenv("REQUESTS")
            .orEmpty()
            .split(' ')
            .filter { it.isNotBlank() }
            .map(::URI)
    if (urls.isEmpty()) {
        System.err.println("REQUESTS is empty")
        return 1
    }
    val downloads = File(System.getenv("DOWNLOADS") ?: "/downloads")
    val host = urls.first().host
    val port = urls.first().port.takeIf { it > 0 } ?: 443
    val peer = singleCandidate(host, port)
    val mode = clientMode()
    val options =
        baseOptions().copy(
            migration =
                when (mode) {
                    ClientMode.Plain -> MigrationPolicy.Forbidden
                    ClientMode.ActiveMigration -> MigrationPolicy.Manual
                },
        )
    return runBlocking(Dispatchers.IO) {
        try {
            when (test) {
                // Two connections: the first fetches one file and keeps the ticket, the second resumes.
                "resumption", "zerortt" -> {
                    val ticket =
                        withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) {
                            download(urls.first(), downloads, ReceivedBytes())
                            withTimeout(10.seconds) {
                                sessionTicket.filterIsInstance<QuicSessionTicketState.Issued>().first().ticket
                            }
                        }
                    val rest = urls.drop(1)
                    if (test == "resumption") {
                        withQuicConnection(peer, options.copy(resumption = QuicResumption.Resume(ticket)), timeout = BLOCK_TIMEOUT) {
                            downloadAll(rest, downloads, ReceivedBytes())
                        }
                    } else {
                        zeroRtt(peer, options, ticket, rest, downloads)
                    }
                }
                // A new connection per file: the handshake is what the loss/corruption is aimed at.
                "multiconnect" ->
                    urls.forEach { url ->
                        withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) { download(url, downloads, ReceivedBytes()) }
                    }
                else ->
                    withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) {
                        when (mode) {
                            ClientMode.Plain -> downloadAll(urls, downloads, ReceivedBytes())
                            ClientMode.ActiveMigration -> downloadAllMigrating(urls, downloads)
                        }
                    }
            }
            0
        } catch (e: Exception) {
            System.err.println("client failed: $e")
            e.printStackTrace()
            1
        }
    }
}

/**
 * One candidate, so the 0-RTT block below runs exactly once: a raced connect runs it once per attempt
 * (see [QuicResumption.ResumeWithEarlyData]), and the streams it hands out must be the winner's.
 */
private fun singleCandidate(
    host: String,
    port: Int,
): QuicPeer {
    val address = InetAddress.getByName(host)
    val family = if (address is Inet6Address) IpFamily.V6 else IpFamily.V4
    return QuicPeer.Candidates(listOf(QuicEndpoint(ResolvedAddress(address.hostAddress, family), port)), serverName = host)
}

/** Every request is written in the first flight as 0-RTT; the responses are read once connected. */
private suspend fun zeroRtt(
    peer: QuicPeer,
    options: QuicOptions,
    ticket: QuicSessionTicket,
    urls: List<URI>,
    downloads: File,
) {
    val early = CompletableDeferred<List<Pair<URI, QuicByteStream>>>()
    val offer =
        QuicResumption.ResumeWithEarlyData(ticket) {
            val opened =
                urls.map { url ->
                    val stream = openStream()
                    writeRequest(stream, url, bufferFactory)
                    url to stream
                }
            early.complete(opened)
        }
    withQuicConnection(peer, options.copy(resumption = offer), timeout = BLOCK_TIMEOUT) {
        val opened = withTimeout(30.seconds) { early.await() }
        println("resumption=$resumption")
        coroutineScope {
            opened.map { (url, stream) -> async { receive(stream, url, downloads, ReceivedBytes()) } }.awaitAll()
        }
    }
}

/** Bytes of response bodies received so far, across every stream of one connection. */
private class ReceivedBytes {
    private val total = AtomicLong()

    fun add(count: Int) {
        total.addAndGet(count.toLong())
    }

    fun total(): Long = total.get()
}

/** What one move came to: the typed answer of its last attempt, or no answer within [MOVE_BUDGET]. */
private sealed interface MoveOutcome {
    class Answered(
        val result: MigrationResult,
        val attempts: Int,
    ) : MoveOutcome

    data object Hung : MoveOutcome
}

/** One move of [ClientMode.ActiveMigration]: which, after how many bytes, what it came to and how long it took. */
private class Move(
    val number: Int,
    val afterBytes: Long,
    val outcome: MoveOutcome,
    val tookMillis: Long,
) {
    val succeeded: Boolean get() = outcome is MoveOutcome.Answered && outcome.result is MigrationResult.Succeeded
}

private fun MoveOutcome.describe(): String =
    when (this) {
        MoveOutcome.Hung -> "Hung (no answer within $MOVE_BUDGET)"
        is MoveOutcome.Answered ->
            when (result) {
                is MigrationResult.Succeeded -> "Succeeded port=${result.localEndpoint.port} attempts=$attempts"
                is MigrationResult.Unmoved -> "$result attempts=$attempts"
            }
    }

/** One move, retrying a [MigrationResult.Unmoved.Failed.NoSpareConnectionId] while the peer may still issue an id. */
private suspend fun QuicScope.moveOnce(): MoveOutcome =
    try {
        withTimeout(MOVE_BUDGET) {
            var attempts = 1
            var result = migrate()
            while (result is MigrationResult.Unmoved.Failed.NoSpareConnectionId && attempts < SPARE_ID_ATTEMPTS) {
                delay(SPARE_ID_WAIT)
                attempts++
                result = migrate()
            }
            MoveOutcome.Answered(result, attempts)
        }
    } catch (hung: TimeoutCancellationException) {
        MoveOutcome.Hung
    }

/**
 * [downloadAll] while moving the connection [MOVES] times, one move each time another [MOVE_EVERY_BYTES]
 * have arrived, so every move happens with response data in flight. Moves stop once the downloads finish.
 */
private suspend fun QuicScope.downloadAllMigrating(
    urls: List<URI>,
    downloads: File,
) {
    val received = ReceivedBytes()
    val moves = mutableListOf<Move>()
    coroutineScope {
        val transfer = async { downloadAll(urls, downloads, received) }
        for (number in 1..MOVES) {
            val threshold = number * MOVE_EVERY_BYTES
            while (received.total() < threshold && !transfer.isCompleted) delay(5.milliseconds)
            if (transfer.isCompleted) break
            val started = TimeSource.Monotonic.markNow()
            val afterBytes = received.total()
            val move = Move(number, afterBytes, moveOnce(), started.elapsedNow().inWholeMilliseconds)
            moves += move
            println(
                "MIGRATION move=${move.number} afterBytes=${move.afterBytes} result=${move.outcome.describe()} " +
                    "tookMs=${move.tookMillis}",
            )
            if (move.outcome is MoveOutcome.Hung) {
                printDriverState("MIGRATION-HUNG")
                System.out.flush()
                break
            }
            System.out.flush()
        }
        transfer.await()
    }
    val driver = (this as QuicheBackedConnection).quicheDriver
    val limit =
        when (val params = driver.peerTransportParams()) {
            PeerTransportParams.NotYetNegotiated -> "not negotiated"
            is PeerTransportParams.Negotiated -> params.activeConnIdLimit.toString()
        }
    val paths =
        when (val snapshot = driver.stallSnapshot()) {
            is Inspected.Read ->
                snapshot.value.paths
                    .map { it.key.port }
                    .toString()
            Inspected.ConnectionGone -> "connection gone"
        }
    val succeeded = moves.count { it.succeeded }
    val leadingSuccesses = moves.takeWhile { it.succeeded }.size
    val firstUnmoved =
        moves
            .filter { !it.succeeded }
            .take(1)
            .joinToString { "move ${it.number}: ${it.outcome.describe()}" }
            .ifEmpty { "none" }
    println(
        "MIGRATION-SUMMARY attempted=${moves.size} succeeded=$succeeded consecutive=$leadingSuccesses " +
            "firstUnmoved=$firstUnmoved " +
            "peerActiveConnIdLimit=$limit pathPortsHeld=$paths bytes=${received.total()}",
    )
    System.out.flush()
}

/** The driver's snapshot and flight recording, each line prefixed with [tag], for a move that hung. */
private suspend fun QuicScope.printDriverState(tag: String) {
    val driver = (this as QuicheBackedConnection).quicheDriver
    println("$tag snapshot ${driver.stallSnapshot()}")
    val recording =
        when (val r = driver.flightRecording()) {
            FlightRecording.Off -> "flight recorder off"
            is FlightRecording.Recorded -> r.render()
        }
    recording.lineSequence().forEach { println("$tag $it") }
}

private suspend fun QuicScope.downloadAll(
    urls: List<URI>,
    downloads: File,
    received: ReceivedBytes,
) {
    // The multiplexing test asks for thousands of files: open them as fast as the peer's stream limit
    // lets, but keep the number of reads in flight bounded.
    val inFlight = Semaphore(100)
    coroutineScope {
        urls.map { url -> async { inFlight.withPermit { download(url, downloads, received) } } }.awaitAll()
    }
}

private suspend fun QuicScope.download(
    url: URI,
    downloads: File,
    received: ReceivedBytes,
) {
    // At the peer's stream limit this waits for its MAX_STREAMS (QuicOptions.streamOpenPolicy defaults
    // to UntilClosed): the multiplexing test asks for far more files than a peer allows streams at once
    // (msquic grants 100), and credit for a finished stream returns only once the peer has seen it finish.
    val stream = openStream()
    writeRequest(stream, url, bufferFactory)
    receive(stream, url, downloads, received)
}

private suspend fun writeRequest(
    stream: QuicByteStream,
    url: URI,
    factory: BufferFactory,
) {
    val request = "GET ${url.path}\r\n"
    val buf = factory.allocate(request.length)
    try {
        buf.writeString(request, Charset.UTF8)
        buf.resetForRead()
        stream.writeFullyAndShutdownSend(buf, 30.seconds)
    } finally {
        buf.freeIfNeeded()
    }
}

private suspend fun receive(
    stream: QuicByteStream,
    url: URI,
    downloads: File,
    received: ReceivedBytes,
) {
    val target = downloads.resolve(url.path.trimStart('/'))
    target.parentFile?.mkdirs()
    try {
        FileOutputStream(target).use { out ->
            while (true) {
                when (val r = stream.read(60.seconds)) {
                    is ReadResult.Data ->
                        try {
                            val count = r.buffer.remaining()
                            out.write(r.buffer.readByteArray(count))
                            received.add(count)
                        } finally {
                            r.buffer.freeIfNeeded()
                        }
                    ReadResult.End -> break
                    ReadResult.Reset -> error("the server reset the stream for ${url.path}")
                }
            }
        }
    } finally {
        stream.close()
    }
}
