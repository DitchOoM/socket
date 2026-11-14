@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.Resolution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The client half of the [quic-interop-runner](https://github.com/quic-interop/quic-interop-runner)
 * endpoint, shared by the JVM endpoint (`QuicInteropEndpoint`) and the Kotlin/Native one
 * (`AppleQuicInteropClient`), so the runner's matrix and the native lanes drive the same client.
 *
 * The contract, read through [InteropHost.env]: `TESTCASE` names the test, `REQUESTS` the URLs
 * (space separated), `DOWNLOADS` the directory each response is written to, and `CLIENT_PARAMS` with
 * `migrate=active` turns `transfer` into [ClientMode.ActiveMigration]. The protocol is `hq-interop`: a
 * request is `GET /<path>\r\n` on a fresh bidirectional stream, sent with FIN; the response is the file.
 */
internal const val HQ = "hq-interop"

/** The exit code the runner reads as "this endpoint does not implement the test". */
internal const val UNSUPPORTED = 127

/**
 * withQuicConnection's timeout bounds the handshake AND the block. The runner's own limit is what should
 * stop a slow test (a 10 MB transfer at 10 Mbit/s under loss outlives the 15 s default), so this is generous.
 */
internal val BLOCK_TIMEOUT = 5.minutes

/**
 * The `TESTCASE` names the client implements, as the runner hands them to the endpoint: `longrtt`
 * arrives as `handshake`; `multiplexing`, the loss, corruption, blackhole, rebind, ipv6 and goodput tests
 * as `transfer`; `handshakeloss`/`handshakecorruption` as `multiconnect`. quiche follows a Retry on its own.
 */
internal val clientTests = setOf("handshake", "transfer", "multiconnect", "retry", "resumption", "zerortt")

/** What the client needs from the platform it runs on. */
internal interface InteropHost {
    /** The environment variable [name], or empty when it is not set. */
    fun env(name: String): String

    /** A new file at [path], its parent directories made. */
    fun create(path: String): InteropDownload

    /** One line of diagnostics on standard error. */
    fun reportError(line: String)

    fun flushOutput()
}

/** A response body being written to disk. */
internal interface InteropDownload {
    fun write(bytes: ByteArray)

    fun close()
}

/** An `https://host[:port]/path` request URL. */
internal class InteropUrl(
    val host: String,
    val port: Int,
    val path: String,
) {
    companion object {
        fun parse(url: String): InteropUrl {
            val rest = url.substringAfter("://")
            val authority = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            val (host, port) =
                if (authority.startsWith('[')) {
                    authority.substring(1, authority.indexOf(']')) to authority.substringAfter("]:", "")
                } else {
                    authority.substringBefore(':') to authority.substringAfter(':', "")
                }
            return InteropUrl(host, port.toIntOrNull() ?: 443, path)
        }
    }
}

internal fun interopBaseOptions() =
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

/** Runs the client for `TESTCASE` and answers the endpoint's exit code. */
internal suspend fun runInteropClient(host: InteropHost): Int {
    val test = host.env("TESTCASE")
    if (test !in clientTests) return UNSUPPORTED
    val urls =
        host
            .env("REQUESTS")
            .split(' ')
            .filter { it.isNotBlank() }
            .map(InteropUrl::parse)
    if (urls.isEmpty()) {
        host.reportError("REQUESTS is empty")
        return 1
    }
    val downloads = Downloads(host, host.env("DOWNLOADS").ifEmpty { "/downloads" })
    val mode = if (host.env("CLIENT_PARAMS").split(' ').contains("migrate=active")) ClientMode.ActiveMigration else ClientMode.Plain
    val options =
        interopBaseOptions().copy(
            migration =
                when (mode) {
                    ClientMode.Plain -> MigrationPolicy.Forbidden
                    ClientMode.ActiveMigration -> MigrationPolicy.Manual
                },
        )
    return try {
        val peer = singleCandidate(urls.first())
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
                        ClientMode.ActiveMigration -> downloadAllMigrating(urls, downloads, host)
                    }
                }
        }
        0
    } catch (e: Exception) {
        host.reportError("client failed: $e")
        e.stackTraceToString().lineSequence().forEach(host::reportError)
        1
    }
}

/** Where responses are written: `DOWNLOADS/<path>`. */
private class Downloads(
    private val host: InteropHost,
    private val directory: String,
) {
    fun create(url: InteropUrl): InteropDownload = host.create(directory.trimEnd('/') + "/" + url.path.trimStart('/'))
}

/**
 * One candidate, so the 0-RTT block below runs exactly once: a raced connect runs it once per attempt
 * (see [QuicResumption.ResumeWithEarlyData]), and the streams it hands out must be the winner's.
 */
private suspend fun singleCandidate(url: InteropUrl): QuicPeer {
    val address =
        when (val resolution = HostResolver.platform().resolve(url.host)) {
            is Resolution.Resolved -> resolution.candidates.first()
            is Resolution.NoAddress -> throw IllegalStateException("${url.host} has no address")
            is Resolution.Failed -> throw resolution.cause
        }
    return QuicPeer.Candidates(listOf(QuicEndpoint(address, url.port)), serverName = url.host)
}

/** Every request is written in the first flight as 0-RTT; the responses are read once connected. */
private suspend fun zeroRtt(
    peer: QuicPeer,
    options: QuicOptions,
    ticket: QuicSessionTicket,
    urls: List<InteropUrl>,
    downloads: Downloads,
) {
    val early = CompletableDeferred<List<Pair<InteropUrl, QuicByteStream>>>()
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
    private val total = AtomicLong(0L)

    fun add(count: Int) {
        total.addAndFetch(count.toLong())
    }

    fun total(): Long = total.load()
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
    urls: List<InteropUrl>,
    downloads: Downloads,
    host: InteropHost,
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
                host.flushOutput()
                break
            }
            host.flushOutput()
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
    host.flushOutput()
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
    urls: List<InteropUrl>,
    downloads: Downloads,
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
    url: InteropUrl,
    downloads: Downloads,
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
    url: InteropUrl,
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
    url: InteropUrl,
    downloads: Downloads,
    received: ReceivedBytes,
) {
    try {
        val out = downloads.create(url)
        try {
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
                    ReadResult.Reset -> throw IllegalStateException("the server reset the stream for ${url.path}")
                }
            }
        } finally {
            out.close()
        }
    } finally {
        stream.close()
    }
}
