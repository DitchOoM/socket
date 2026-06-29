package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.WebTransportStream
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions

/**
 * **Not a unit test** — a manually-driven external WebTransport server for **browser** interop
 * validation (handoff §3). A real browser cannot run the in-process [WebTransportTestSuite] (it has no
 * in-process QUIC server), so its WebTransport client (the production `browserMain` wrapper, or a raw
 * `new WebTransport(...)`) must dial an *externally-launched* `withHttp3Server`. This is that server.
 *
 * It is gated behind the `wt.interop.server` system property so a normal `jvmTest` run skips it
 * (JUnit assumption) — it only runs when explicitly launched:
 *
 * ```
 * JAVA_HOME=<jdk21> ./gradlew :socket-webtransport:jvmTest \
 *   --tests 'com.ditchoom.socket.webtransport.BrowserInteropServer' \
 *   -Dwt.interop.server=true
 * ```
 *
 * It binds an **ephemeral** UDP port (`port = 0`) and prints `WT_SERVER_READY port=<n> certSha256=<hex>`
 * on stdout — the caller captures the real port (no fixed-port collisions) and uses the printed SHA-256
 * (the DER hash of the leaf) as the W3C `serverCertificateHashes` value. The server presents the
 * **`pinned`** fixture (EC P-256, ≤14-day validity), the only self-signed leaf a browser accepts via
 * `serverCertificateHashes`. It serves until a stop file appears (`wt.interop.stopFile`, default
 * `$TMPDIR/wt-interop-stop`) or [MAX_LIFETIME] elapses, so an orchestrator (Karma) can stop it cleanly.
 *
 * Per accepted session it echoes: each peer bidi stream back prefixed `echo:` then FIN; each peer uni
 * stream drained (logged); each datagram back prefixed `echo:`.
 */
class BrowserInteropServer {
    @Test
    fun runUntilStopped() {
        assumeTrue(
            "BrowserInteropServer only runs with -Dwt.interop.server=true (manual browser interop harness)",
            System.getProperty("wt.interop.server") == "true",
        )
        // Cert selection. Default `pinned` (EC P-256 ≤14-day) for the browser cell — the only self-signed
        // leaf a browser accepts via serverCertificateHashes. `cert` is the long-lived testcerts/cert.*
        // (CN=quic.tech), used by the durable native↔native cross-impl cell (quiche-server ↔ NW-client),
        // where the client dials with verifyPeer=false and never pins, so the 13-day pinned treadmill is
        // unnecessary.
        val certName = System.getProperty("wt.interop.cert", "pinned")
        val certCrt = resolveCert("$certName.crt")
        val certKey = resolveCert("$certName.key")
        val certSha256 =
            if (certName == "pinned") resolveCert("$certName.sha256").readText().trim() else ""
        val stopFile =
            File(System.getProperty("wt.interop.stopFile") ?: File(System.getProperty("java.io.tmpdir"), "wt-interop-stop").path)
        stopFile.delete() // clear any stale sentinel from a previous run

        // verifyPeer=false: WebTransport clients present no client cert, so there is nothing to verify on
        // the server side. The browser independently checks the leaf against serverCertificateHashes.
        val serverQuicOptions =
            QuicOptions(
                alpnProtocols = listOf(HTTP3_ALPN),
                verifyPeer = false,
                idleTimeout = 5.minutes,
                datagrams = DatagramOptions(),
            )
        val connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic())

        runBlocking {
            withContext(Dispatchers.Default) {
                withHttp3Server(
                    port = 0,
                    tlsConfig = QuicTlsConfig(certChainPath = certCrt.absolutePath, privKeyPath = certKey.absolutePath),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 16),
                    onWebTransport = { echoSession() },
                    onRequest = { response.send(404) },
                ) {
                    // The block runs with `port` resolved to the bound ephemeral port. Print a single
                    // machine-parseable READY line; flush so a capturing orchestrator sees it immediately.
                    println("WT_SERVER_READY port=$port certSha256=$certSha256 stopFile=${stopFile.absolutePath}")
                    System.out.flush()
                    // Also emit a machine-readable config file (the orchestrator's signal that the server is
                    // up AND the port/hash the browser test compiles against). Written last so its existence
                    // means "ready". Gradle captures test-worker stdout into the report, not the console, so a
                    // file is the reliable cross-process channel.
                    System.getProperty("wt.interop.configFile")?.let { cfg ->
                        File(cfg).apply {
                            parentFile?.mkdirs()
                            // datagrams=true: this quiche server can carry WT datagrams (RFC 9297). A
                            // cross-impl client involving NW ignores this and tests streams only.
                            writeText("url=https://localhost:$port/\ncertSha256=$certSha256\ndatagrams=true\n")
                        }
                    }
                    val deadline = MAX_LIFETIME.inWholeMilliseconds
                    var waited = 0L
                    while (!stopFile.exists() && waited < deadline) {
                        delay(POLL_MS)
                        waited += POLL_MS
                    }
                    println("WT_SERVER_STOPPING (stopFile=${stopFile.exists()}, waitedMs=$waited)")
                }
            }
        }
    }

    /** Echo every peer-initiated bidi/uni stream + datagram for one accepted session, until it closes. */
    private suspend fun com.ditchoom.socket.http3.WebTransportServerExchange.echoSession() {
        val session = accept()
        // The `/reset` path drives the browser stream-reset parity test: instead of echoing, the server
        // aborts each incoming bidi stream with BROWSER_RESET_CODE so the browser client observes the
        // neutral WebTransportStreamException carrying that 32-bit code (matching the native suite).
        val resetMode = path.endsWith("reset")
        println("WT_SESSION_ACCEPTED id=${session.sessionId} authority=$authority path=$path resetMode=$resetMode")
        coroutineScope {
            launch {
                session.incomingBidiStreams.collect { stream ->
                    // Launch per stream so concurrent bidi streams echo (or reset) independently.
                    launch { runCatching { if (resetMode) resetBidi(stream) else echoBidi(stream) } }
                }
            }
            launch {
                session.incomingUniStreams.collect { uni ->
                    launch {
                        val text = runCatching { uni.readUtf8() }.getOrDefault("<error>")
                        println("WT_UNI_RECEIVED id=${session.sessionId} bytes=${text.length} text=$text")
                    }
                }
            }
            launch {
                session.datagrams.collect { dg ->
                    val text = dg.readString(dg.remaining(), Charset.UTF8)
                    dg.freeIfNeeded()
                    runCatching { session.sendDatagram(textBuffer("echo:$text")) }
                }
            }
        }
    }

    private suspend fun echoBidi(stream: WebTransportStream) {
        val msg = stream.readUtf8()
        stream.write(textBuffer("echo:$msg"))
        (stream as HalfCloseable).shutdownSend()
        stream.close()
    }

    /** Read the opener's first chunk, then abort the stream with [BROWSER_RESET_CODE] (RESET_STREAM + STOP_SENDING). */
    private suspend fun resetBidi(stream: WebTransportStream) {
        runCatching { stream.read() }
        (stream as Resettable).reset(BROWSER_RESET_CODE.toLong())
    }

    private fun resolveCert(name: String): File {
        // jvmTest's working dir is the module dir (socket-webtransport/). Both the short-lived `pinned`
        // W3C fixture (generated, gitignored, by :socket-webtransport:generateWebTransportPinnedCert) and
        // the long-lived `cert` PEM live under this module's own testcerts/.
        val candidates =
            listOf(
                File("testcerts/$name"),
                File("socket-webtransport/testcerts/$name"),
            )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "cert fixture '$name' not found (tried ${candidates.map { it.absolutePath }}). " +
                    "Run ':socket-webtransport:generateWebTransportPinnedCert' (pinned) or ensure testcerts/cert.* exists.",
            )
    }

    private companion object {
        val MAX_LIFETIME = 30.minutes
        const val POLL_MS = 500L

        /**
         * The WebTransport application error code the `/reset` route aborts streams with. 0x1e7 straddles
         * a §4.3 skip boundary (so a naive pass-through would not round-trip), matching the native suite's
         * reset test. Kept in sync with [BrowserWebTransportInteropTest]'s expected value (different source
         * sets — can't share a constant).
         */
        const val BROWSER_RESET_CODE: UInt = 0x1e7u
    }
}

private fun textBuffer(s: String): PlatformBuffer =
    BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
        writeString(s, Charset.UTF8)
        resetForRead()
    }

private suspend fun WebTransportStream.readUtf8(): String = drainUtf8 { read() }

private suspend fun ByteSource.readUtf8(): String = drainUtf8 { read() }

private suspend fun drainUtf8(read: suspend () -> ReadResult): String {
    val sb = StringBuilder()
    while (true) {
        when (val result = read()) {
            is ReadResult.Data -> {
                sb.append(result.buffer.readString(result.buffer.remaining(), Charset.UTF8))
                result.buffer.freeIfNeeded()
            }
            ReadResult.End, ReadResult.Reset -> return sb.toString()
        }
    }
}
