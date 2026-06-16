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
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.ditchoom.socket.http3.WebTransportOptions as Http3WebTransportOptions

/**
 * Cross-module WebTransport conformance suite, driving the **public** stack end to end: a real HTTP/3
 * WebTransport server ([withHttp3Server] + its `onWebTransport` accept hook) on `localhost`, and a real
 * native client through the neutral [webTransportSupport] API ([WebTransportSupport.connect] and the
 * native-only [WebTransportSupport.Multiplexed.connectMultiplexed]). No socket-http3 codec internals are
 * touched — only the same public entrypoints application code uses — which is why this suite lives in
 * `:socket-testsuite` (shared, MAIN source set) while socket-http3's white-box loopback server stays in
 * its own `commonTest`.
 *
 * Platform-parameterized exactly like the QUIC `*TestSuite`s: each platform with a working in-process
 * QUIC server (JVM, linuxX64, and Apple via Network.framework) subclasses this and supplies
 * [testTlsConfig] (cert/key paths) + [wrapTestBody] (skip when the native QUIC binding is absent). JS /
 * wasmJs have no in-process QUIC server and no native multiplexed provider, so they get no subclass.
 *
 * The **DONE bar** for v6 Phase 4 is [multiplexed_twoSessionsOverOneConnection_eachRoundTrip]: many
 * WebTransport sessions over a single held HTTP/3 connection, each with working streams.
 */
abstract class WebTransportTestSuite {
    /** Server cert + key for the in-process WebTransport server (platform-specific path resolution). */
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Skip-on-missing-native-lib hook; JVM overrides to translate `UnsatisfiedLinkError` to a skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * Open a single WebTransport session to [url], **trusting the self-signed loopback cert**. Delegated
     * to the subclass because the trust knob (peer-verification / pinned anchors) lives in the native
     * [Http3WebTransportConfig][com.ditchoom.socket.webtransport.Http3WebTransportConfig] — an
     * `http3Main` type this common suite can't name. The subclass dials via the native `connect(url,
     * config)` overload with `verifyPeer = false` (the loopback cert is self-signed; this suite tests
     * WebTransport, not chain validation — mirroring the QUIC/h3 loopback suites).
     */
    protected abstract suspend fun openSingleSession(url: String): WebTransportSession

    /** Multiplexed counterpart of [openSingleSession]: a held connection dialed with the loopback trust. */
    protected abstract suspend fun openMultiplexed(url: String): MultiplexedWebTransport

    // Datagrams enabled on the server so WebTransport datagrams (RFC 9297) are negotiable; the neutral
    // client's connectMultiplexed/connect already enable them client-side. verifyPeer=false because the
    // loopback cert is self-signed; the suite is about WebTransport, not cert validation.
    private val serverQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(),
        )

    // QUIC stream I/O is zero-copy: it reads each buffer's native address. On Kotlin/Native,
    // BufferFactory.Default allocates heap (no native memory), so the server's frame/body buffers MUST
    // come from a native-memory-backed factory (deterministic()) — the same choice the QUIC + h3 suites
    // make. (The client's factory is whatever connectMultiplexed/connect uses internally.)
    private val connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic())

    @Test
    fun connect_roundTripsBidiStream(): TestResult =
        runWebTransportTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 4),
                    onWebTransport = { echoFirstBidiStream() },
                    onRequest = { response.send(404) },
                ) {
                    delay(SETTLE) // let the server's control stream + SETTINGS go out before we dial
                    val session = openSingleSession("https://localhost:$port/wt")
                    try {
                        assertEquals("echo:hello", session.roundTripBidi("hello"))
                    } finally {
                        session.close()
                    }
                }
            }
        }

    @Test
    fun multiplexed_twoSessionsOverOneConnection_eachRoundTrip(): TestResult =
        runWebTransportTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 4),
                    // onWebTransport runs once per accepted CONNECT — i.e. once per session — so each of
                    // the two sessions opened below gets its own echo handler over the one connection.
                    onWebTransport = { echoFirstBidiStream() },
                    onRequest = { response.send(404) },
                ) {
                    delay(SETTLE)
                    assertTrue(
                        webTransportSupport() is WebTransportSupport.Multiplexed,
                        "native webTransportSupport() must be Multiplexed (the v6 type-gated capability)",
                    )
                    val held = openMultiplexed("https://localhost:$port/")
                    try {
                        val a = held.openSession("/a")
                        val b = held.openSession("/b")
                        // Both sessions ride the SINGLE held HTTP/3 connection; each round-trips its own
                        // bidi stream independently — the Phase-4 DONE bar.
                        assertEquals("echo:from-a", a.roundTripBidi("from-a"))
                        assertEquals("echo:from-b", b.roundTripBidi("from-b"))
                        a.close()
                        b.close()
                    } finally {
                        held.close()
                    }
                }
            }
        }

    @Test
    fun multiplexed_close_isIdempotentAndTearsDown(): TestResult =
        runWebTransportTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 4),
                    onWebTransport = { echoFirstBidiStream() },
                    onRequest = { response.send(404) },
                ) {
                    delay(SETTLE)
                    assertTrue(webTransportSupport() is WebTransportSupport.Multiplexed)
                    val held = openMultiplexed("https://localhost:$port/")
                    val session = held.openSession("/a")
                    assertEquals("echo:ping", session.roundTripBidi("ping"))
                    // close() cancels the held scope (tears down the connection + every session); a second
                    // close() must be a no-op, not an error.
                    held.close()
                    held.close()
                }
            }
        }
}

/** Server-side echo: accept the session, read its first peer bidi stream, write it back prefixed, FIN. */
private suspend fun com.ditchoom.socket.http3.WebTransportServerExchange.echoFirstBidiStream() {
    val session = accept()
    val stream = session.incomingBidiStreams.first()
    val msg = stream.readUtf8()
    stream.write(textBuffer("echo:$msg"))
    stream.close()
}

/** Client-side: open a bidi stream, send [msg], half-close the send side, read the echoed reply. */
private suspend fun WebTransportSession.roundTripBidi(msg: String): String {
    val stream = openBidiStream()
    stream.write(textBuffer(msg))
    // The send-side FIN tells the server "end of request" while keeping the read side open for the echo
    // (RFC 9114 §4 half-close). Native WebTransport bidi streams are HalfCloseable (Phase-3a / A2).
    (stream as HalfCloseable).shutdownSend()
    return withTimeout(5.seconds) { stream.readUtf8() }
}

/** Drain a [ByteSource] to end-of-stream as a UTF-8 string. */
private suspend fun ByteSource.readUtf8(): String {
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

/**
 * A native-memory (zero-copy-safe) buffer holding [s] as UTF-8, positioned for reading. Test bodies are
 * ASCII, so byte length == char length. Native memory is required because QUIC stream writes read the
 * buffer's native address (see [WebTransportTestSuite.connectionOptions]).
 */
private fun textBuffer(s: String): PlatformBuffer =
    BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
        writeString(s, Charset.UTF8)
        resetForRead()
    }

/** Wall-clock-timed runner on a real dispatcher (no virtual time), mirroring the QUIC suites' runQuicTest. */
private fun runWebTransportTest(
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestResult =
    runTest(timeout = timeout + 15.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(timeout) { block() }
        }
    }

/** Settle time for the server's control stream + SETTINGS to be sent before a client dials. */
private const val SETTLE = 100L
