@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.http3.WebTransportServerExchange
import com.ditchoom.socket.http3.WebTransportStream
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions

/**
 * **Not a unit test** — a manually-launched external WebTransport server backed by **Network.framework**
 * (Apple K/N), for **cross-implementation** interop validation (handoff "NEXT-PHASE PLAN", cell #1). Every
 * `WebTransportTestSuite` subclass is SAME-platform loopback (server + client in one process, one impl);
 * this runner is the NW half of a *cross-process, cross-impl* pair — an NW server reachable over localhost
 * by a **quiche** client (the JVM [QuicheInteropClient]) so the two native QUIC/H3 backends are exercised
 * across the wire, not just against themselves.
 *
 * It mirrors [BrowserInteropServer] (the quiche/JVM counterpart) but:
 *  - is gated by the **`WT_INTEROP_SERVER` environment variable** (K/N has no JVM system properties), so a
 *    normal `macosArm64Test` run skips it via an early return;
 *  - presents its identity from a **PKCS#12** blob (`sec_identity_t` — NW can't build one from loose PEM;
 *    see [QuicTlsConfig.pkcs12Path]). `WT_INTEROP_CERT=cert` (default) uses the long-lived
 *    `testcerts/cert.p12`; `WT_INTEROP_CERT=pinned` uses the EC P-256 ≤14-day `pinned.p12` for the
 *    NW-server ↔ Chrome cell (browser `serverCertificateHashes`).
 *
 * Config is injected at **runtime** (not compile time): the server binds an ephemeral port (`port = 0`)
 * and writes `url`/`certSha256`/`datagrams` to `WT_INTEROP_CONFIG_FILE`; the client reads it back. Written
 * last so its existence is the readiness signal. It serves until `WT_INTEROP_STOP_FILE` appears (the
 * orchestrator's clean-stop channel) or [MAX_LIFETIME] elapses.
 *
 * **Datagrams are intentionally absent from cross-impl coverage**: NW can't deliver inbound streams and a
 * datagram flow together (documented on [WebTransportSession]), and `withHttp3Server` forces `PreferStreams`
 * — so the written `datagrams=false` tells the client to test streams only.
 */
class NwInteropServer {
    @Test
    fun runUntilStopped() {
        if (env("WT_INTEROP_SERVER") != "true") return // gated: skip on ordinary macosArm64Test runs

        val configFile = env("WT_INTEROP_CONFIG_FILE") ?: error("WT_INTEROP_CONFIG_FILE not set")
        val stopFile = env("WT_INTEROP_STOP_FILE") ?: error("WT_INTEROP_STOP_FILE not set")
        deleteFile(stopFile) // clear any stale sentinel from a previous run

        val cert = resolveCert(env("WT_INTEROP_CERT") ?: "cert")
        val certSha256 = cert.sha256Path?.let { readTextFile(it).trim() } ?: ""

        // verifyPeer=false: WebTransport clients present no client cert. The browser (Chrome cell) checks
        // the leaf against serverCertificateHashes; a quiche client dials with verifyPeer=false too.
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
                    tlsConfig =
                        QuicTlsConfig(
                            certChainPath = cert.crtPath,
                            privKeyPath = cert.keyPath,
                            pkcs12Path = cert.p12Path,
                            pkcs12Password = "testpass",
                        ),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 16),
                    onWebTransport = { echoSession() },
                    onRequest = { response.send(404) },
                ) {
                    println("WT_SERVER_READY backend=nw port=$port certSha256=$certSha256")
                    // The config file is the cross-process readiness signal AND the port/hash the client
                    // dials. Written last so its existence means "up". Cross-impl is streams-only.
                    writeTextFile(configFile, "url=https://localhost:$port/\ncertSha256=$certSha256\ndatagrams=false\n")

                    val deadline = MAX_LIFETIME.inWholeMilliseconds
                    var waited = 0L
                    while (!fileExists(stopFile) && waited < deadline) {
                        delay(POLL_MS)
                        waited += POLL_MS
                    }
                    println("WT_SERVER_STOPPING (stopFile=${fileExists(stopFile)}, waitedMs=$waited)")
                }
            }
        }
    }

    /** Echo every peer-initiated bidi stream back prefixed `echo:`; log each peer uni stream. */
    private suspend fun WebTransportServerExchange.echoSession() {
        val session = accept()
        println("WT_SESSION_ACCEPTED id=${session.sessionId} authority=$authority path=$path")
        coroutineScope {
            launch {
                session.incomingBidiStreams.collect { stream ->
                    launch { runCatching { echoBidi(stream) } }
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
        }
    }

    private suspend fun echoBidi(stream: WebTransportStream) {
        val msg = stream.readUtf8()
        stream.write(textBuffer("echo:$msg"))
        (stream as HalfCloseable).shutdownSend()
        stream.close()
    }

    private class Cert(
        val crtPath: String,
        val keyPath: String,
        val p12Path: String,
        val sha256Path: String?,
    )

    /**
     * Resolve a cert bundle by base name. `cert` → the long-lived `testcerts/cert.*` (self-signed EC
     * P-256, CN=localhost), good for durable native↔native cells — EC deliberately, because an NW QUIC
     * server must keep its cert flight small to interoperate with non-Apple clients (Network.framework
     * under-counts the client Initial for RFC 9000 §8.1 anti-amplification; a large/RSA cert deadlocks
     * the handshake — see the limitation note on the Apple buildAppleQuicServer). `pinned` →
     * `socket-quic-nw/testcerts/pinned.*` (EC P-256, ≤14-day) for the Chrome cell's
     * `serverCertificateHashes`. The macosArm64Test working dir is the module dir.
     */
    private fun resolveCert(name: String): Cert =
        when (name) {
            "pinned" ->
                Cert(
                    crtPath = firstExisting("../socket-quic-nw/testcerts/pinned.crt", "socket-quic-nw/testcerts/pinned.crt"),
                    keyPath = firstExisting("../socket-quic-nw/testcerts/pinned.key", "socket-quic-nw/testcerts/pinned.key"),
                    p12Path = firstExisting("../socket-quic-nw/testcerts/pinned.p12", "socket-quic-nw/testcerts/pinned.p12"),
                    sha256Path = firstExisting("../socket-quic-nw/testcerts/pinned.sha256", "socket-quic-nw/testcerts/pinned.sha256"),
                )
            else ->
                Cert(
                    crtPath = firstExisting("testcerts/cert.crt", "socket-webtransport/testcerts/cert.crt"),
                    keyPath = firstExisting("testcerts/cert.key", "socket-webtransport/testcerts/cert.key"),
                    p12Path = firstExisting("testcerts/cert.p12", "socket-webtransport/testcerts/cert.p12"),
                    sha256Path = null,
                )
        }

    private fun firstExisting(vararg candidates: String): String =
        candidates.firstOrNull { fileExists(it) }
            ?: error("file not found (tried ${candidates.toList()}; did the cert task run?)")

    private companion object {
        val MAX_LIFETIME = 30.minutes
        const val POLL_MS = 500L
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
