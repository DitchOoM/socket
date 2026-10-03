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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
        stream.shutdownSend()
    } catch (e: Exception) {
        System.err.println("request failed: $e")
    } finally {
        stream.close()
    }
}

private suspend fun QuicScope.sendFile(
    stream: QuicByteStream,
    file: File,
) {
    val chunk = 64 * 1024
    val buf = bufferFactory.allocate(chunk)
    try {
        file.inputStream().use { input ->
            val bytes = ByteArray(chunk)
            while (true) {
                val n = input.read(bytes)
                if (n <= 0) break
                buf.resetForWrite()
                buf.writeBytes(bytes, 0, n)
                buf.resetForRead()
                stream.writeFully(buf, 60.seconds)
            }
        }
    } finally {
        buf.freeIfNeeded()
    }
}

// ---- client -------------------------------------------------------------------------------------

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
    val options = baseOptions().copy(migration = MigrationPolicy.Forbidden)
    return runBlocking(Dispatchers.IO) {
        try {
            when (test) {
                // Two connections: the first fetches one file and keeps the ticket, the second resumes.
                "resumption", "zerortt" -> {
                    val ticket =
                        withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) {
                            download(urls.first(), downloads)
                            withTimeout(10.seconds) {
                                sessionTicket.filterIsInstance<QuicSessionTicketState.Issued>().first().ticket
                            }
                        }
                    val rest = urls.drop(1)
                    if (test == "resumption") {
                        withQuicConnection(peer, options.copy(resumption = QuicResumption.Resume(ticket)), timeout = BLOCK_TIMEOUT) {
                            downloadAll(rest, downloads)
                        }
                    } else {
                        zeroRtt(peer, options, ticket, rest, downloads)
                    }
                }
                // A new connection per file: the handshake is what the loss/corruption is aimed at.
                "multiconnect" ->
                    urls.forEach { url ->
                        withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) { download(url, downloads) }
                    }
                else -> withQuicConnection(peer, options, timeout = BLOCK_TIMEOUT) { downloadAll(urls, downloads) }
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
            opened.map { (url, stream) -> async { receive(stream, url, downloads) } }.awaitAll()
        }
    }
}

private suspend fun QuicScope.downloadAll(
    urls: List<URI>,
    downloads: File,
) {
    // The multiplexing test asks for thousands of files: open them as fast as the peer's stream limit
    // lets, but keep the number of reads in flight bounded.
    val inFlight = Semaphore(100)
    coroutineScope {
        urls.map { url -> async { inFlight.withPermit { download(url, downloads) } } }.awaitAll()
    }
}

private suspend fun QuicScope.download(
    url: URI,
    downloads: File,
) {
    val stream = openStream()
    writeRequest(stream, url, bufferFactory)
    receive(stream, url, downloads)
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
        stream.writeFully(buf, 30.seconds)
    } finally {
        buf.freeIfNeeded()
    }
    stream.shutdownSend()
}

private suspend fun receive(
    stream: QuicByteStream,
    url: URI,
    downloads: File,
) {
    val target = downloads.resolve(url.path.trimStart('/'))
    target.parentFile?.mkdirs()
    try {
        FileOutputStream(target).use { out ->
            while (true) {
                when (val r = stream.read(60.seconds)) {
                    is ReadResult.Data ->
                        try {
                            out.write(r.buffer.readByteArray(r.buffer.remaining()))
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
