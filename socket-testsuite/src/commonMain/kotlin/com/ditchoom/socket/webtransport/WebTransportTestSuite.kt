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
import com.ditchoom.socket.http3.withHttp3Server
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
 *
 * **No settle delay before dialing** (issue #305). Every test here opened with a `delay(SETTLE)` "let
 * the server's control stream + SETTINGS go out before we dial" beat. It waited for nothing:
 * [withHttp3Server] wraps `withQuicServer`, which has already **bound** by the time its block runs, and
 * it launches its accept job before running the block; accepted connections then queue in
 * `ServerConnectionRegistry.acceptedDrivers` (`Channel.UNLIMITED`) from the moment of bind. The server's
 * control stream cannot go out *before* a client dials in any case — it is opened per accepted
 * connection — and the client's own `peerSettings()` gate is what actually waits for it. The only delay
 * left is the poll interval inside the bounded reset-observation loop.
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

    /**
     * Per-test failure diagnostics. Fresh per test instance, dumped by [runWebTransportTest] on any
     * failure and silent otherwise — see [WebTransportDiagnostics] for why this exists.
     */
    internal val diagnostics = WebTransportDiagnostics()

    /**
     * The sink each platform subclass wires into the QUIC options of the connection it dials, so the
     * failure report carries the client's side of the wire as well as the server's.
     *
     * `protected` rather than exposing [diagnostics]: the subclasses live in another module, and this is
     * the only piece of it their client config needs. Nothing else about the diagnostics is theirs.
     */
    protected val clientTraceSink: TraceSink get() = diagnostics.clientSink

    // Datagrams enabled on the server so WebTransport datagrams (RFC 9297) are negotiable; the neutral
    // client's connectMultiplexed/connect already enable them client-side. verifyPeer=false because the
    // loopback cert is self-signed; the suite is about WebTransport, not cert validation.
    //
    // trace: server-side deterministic-replay capture (RFC_DETERMINISTIC_SIMULATION.md §5) into an
    // in-memory buffer. The single-sink convenience constructor is deliberate — every accepted
    // connection interleaves onto one buffer, which is aggregate DIAGNOSTICS rather than per-connection
    // replay, and interleaving is exactly what we want when the question is "which of two concurrent
    // connections stopped making progress".
    private val serverQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(),
            trace = QuicTraceCapture(diagnostics.sink),
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
                    val session = openSingleSession("https://localhost:$port/wt")
                    try {
                        assertEquals("echo:hello", session.roundTripBidi("hello"))

                        // The diagnostics are only ever read on a failure, so nothing else in this suite
                        // would notice them going quiet — a subclass that stopped passing the sink into
                        // its client config, or a capture that never reaches the dialling engine on some
                        // platform, would leave every future failure report saying "0 events" and reading
                        // exactly like "the client sent nothing". That is the reading the client trace was
                        // added to rule out, so it is asserted on the one path known to have sent packets.
                        assertTrue(
                            diagnostics.clientEventCount > 0,
                            "client QUIC trace is empty after a completed round trip: the capture is not " +
                                "wired on this platform, so a failure report here cannot tell a client that " +
                                "sent nothing from one that sent into the void",
                        )
                        assertTrue(
                            diagnostics.serverEventCount > 0,
                            "server QUIC trace is empty after a completed round trip",
                        )
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
    fun connect_peerResetsStream_surfacesNeutralExceptionWithCode(): TestResult =
        runWebTransportTest {
            wrapTestBody {
                // A code that straddles a §4.3 skip boundary, so a naive pass-through would not round-trip.
                // 32-bit unsigned WebTransport application code (draft §4.3).
                val wtCode = 0x1e7u
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = Http3WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        val stream = session.incomingBidiStreams.first()
                        // Read the opener's first chunk, then abort the stream with a WebTransport code.
                        withTimeout(5.seconds) { stream.read() }
                        (stream as Resettable).reset(wtCode.toLong()) // Resettable.reset is the buffer-flow Long contract
                    },
                    onRequest = { response.send(404) },
                ) {
                    val session = openSingleSession("https://localhost:$port/wt")
                    try {
                        val observed =
                            withTimeout(5.seconds) {
                                val stream = session.openBidiStream()
                                stream.write(textBuffer("hello"))
                                // Keep writing until the peer's STOP_SENDING surfaces as the NEUTRAL
                                // WebTransportStreamException — the same type + 32-bit code the browser
                                // backend raises (this is the cross-backend exception-parity guard).
                                var code: UInt? = null
                                while (code == null) {
                                    try {
                                        stream.write(textBuffer("x"))
                                        delay(25)
                                    } catch (e: WebTransportStreamException) {
                                        code = e.errorCode
                                    }
                                }
                                code
                            }
                        assertEquals(wtCode, observed)
                    } finally {
                        session.close()
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

    @Test
    fun connect_twoSessions_areDedicatedConnections_notTransparentlyPooled(): TestResult =
        runWebTransportTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    // onWebTransport fires once per accepted CONNECT — i.e. once per session/connection —
                    // so each of the two independent connect()s below gets its own one-shot echo handler.
                    webTransport = Http3WebTransportOptions(maxSessions = 4),
                    onWebTransport = { echoFirstBidiStream() },
                    onRequest = { response.send(404) },
                ) {
                    diagnostics.mark("server bound; dialing first")
                    // Two separate connect() calls to the SAME authority. On native each dials a DEDICATED
                    // HTTP/3 connection — WebTransportOptions.allowPooling is a documented no-op here (never
                    // mapped in WebTransportSupportHttp3.connectInternal); transparent pooling is browser-only.
                    // Proof they are not pooled onto one shared connection: in the held-lifetime model
                    // session.close() tears down THAT session's own connection, so closing the first must
                    // leave the second fully usable. (If they shared one pooled connection, the second
                    // round-trip below would fail after the first close.)
                    val first = openSingleSession("https://localhost:$port/wt")
                    diagnostics.mark("first connected; dialing second")
                    val second = openSingleSession("https://localhost:$port/wt")
                    try {
                        assertEquals("echo:one", first.roundTripBidi("one", diagnostics))
                        diagnostics.mark("first round-trip ok; closing first")
                        first.close()
                        diagnostics.mark("first closed; round-tripping second")
                        assertEquals("echo:two", second.roundTripBidi("two", diagnostics))
                        diagnostics.mark("both round-trips ok")
                    } finally {
                        first.close()
                        second.close()
                    }
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
private suspend fun WebTransportSession.roundTripBidi(
    msg: String,
    diagnostics: WebTransportDiagnostics? = null,
): String {
    diagnostics?.mark("rt[$msg]: openBidiStream")
    val stream = openBidiStream()
    diagnostics?.mark("rt[$msg]: write")
    stream.write(textBuffer(msg))
    // The send-side FIN tells the server "end of request" while keeping the read side open for the echo
    // (RFC 9114 §4 half-close). Native WebTransport bidi streams are HalfCloseable (Phase-3a / A2).
    diagnostics?.mark("rt[$msg]: shutdownSend")
    (stream as HalfCloseable).shutdownSend()
    diagnostics?.mark("rt[$msg]: awaiting echo")
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

/**
 * Wall-clock-timed runner on a real dispatcher (no virtual time), mirroring the QUIC suites' runQuicTest.
 *
 * On ANY failure it prints [WebTransportDiagnostics.report] before rethrowing, so a failure carries the
 * step it reached and the server's QUIC trace instead of a bare `Timed out waiting for 30000 ms`. That
 * output lands in the test XML's `system-out`, which CI already uploads (`test-reports-linux`), so a
 * rare CI-only failure is diagnosable from its FIRST occurrence rather than needing a reproduction.
 */
private fun WebTransportTestSuite.runWebTransportTest(
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestResult =
    runTest(timeout = timeout + 15.seconds) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeout) { block() }
            } catch (t: Throwable) {
                println(diagnostics.report(t))
                throw t
            }
        }
    }

/**
 * Failure diagnostics for this suite: the last step the test body reached, plus the tail of the QUIC
 * trace from **both** ends of the connection.
 *
 * WHY: `connect_twoSessions_areDedicatedConnections_notTransparentlyPooled[linuxX64]` has failed on CI
 * as an opaque `TimeoutCancellationException: Timed out waiting for 30000 ms`. Only the echo read is
 * bounded (`withTimeout(5.seconds)`), so a 30 s failure proves the stall is in one of the UNBOUNDED
 * calls — connect, openBidiStream, write, shutdownSend, close — and the bare exception cannot say
 * which. It reproduces roughly never (once in ~120 local runs), so waiting for a local repro is not a
 * strategy; capturing on the failure we DO get is.
 *
 * [mark] is a plain atomic store on the happy path — deliberately not `println`, which serialises on
 * IO and was measured to perturb this race away entirely.
 */
@OptIn(ExperimentalAtomicApi::class)
class WebTransportDiagnostics {
    private val lastStep = AtomicReference("(not started)")
    private val serverEvents = AtomicReference(emptyList<String>())
    private val clientEvents = AtomicReference(emptyList<String>())

    /** Records the step the test body is about to attempt. Cheap enough to leave on always. */
    fun mark(step: String) {
        lastStep.store(step)
    }

    /**
     * The server-side capture sink. Bounded to [MAX_EVENTS] most-recent lines: a stalled connection can
     * emit steadily (timer wakes, path polls) and the TAIL is what shows where progress stopped, so an
     * unbounded buffer would only risk memory for older, less useful lines.
     */
    val sink: TraceSink = ringSink(serverEvents)

    /**
     * The client-side capture sink, for the dialling connection.
     *
     * WHY BOTH SIDES: on 2026-08-14 `connect_peerResetsStream_surfacesNeutralExceptionWithCode` failed
     * on `build-apple / Integration Tests (macOS ARM64)` with `QUIC handshake failed [IdleTimeout]`,
     * `last step reached: (not started)`, and **`server QUIC trace (0 most recent events)`**. A
     * server-only capture cannot say what an empty server trace means: the client may have sent nothing,
     * or it may have sent into the void. Those have different fixes and the report could not tell them
     * apart, so the run was unactionable.
     *
     * With both sides the next occurrence answers it directly. Client `DGRAM_OUT` lines with no server
     * `DGRAM_IN` puts the loss on the wire — and each line carries its 4-tuple, which is where a
     * `localhost` that resolved to `::1` against a server bound v4-only would show itself. No client
     * `DGRAM_OUT` at all puts it before the wire, in the engine or the dial.
     */
    val clientSink: TraceSink = ringSink(clientEvents)

    /** How many lines each side has recorded. Read by the suite to prove the capture is live. */
    val clientEventCount: Int get() = clientEvents.load().size
    val serverEventCount: Int get() = serverEvents.load().size

    fun report(cause: Throwable): String =
        buildString {
            appendLine("=== WebTransport failure diagnostics ===")
            appendLine("cause: ${cause::class.simpleName}: ${cause.message}")
            appendLine("last step reached: ${lastStep.load()}")
            appendTrace("client", clientEvents.load())
            appendTrace("server", serverEvents.load())
            appendLine("=== end diagnostics ===")
        }

    private fun StringBuilder.appendTrace(
        side: String,
        captured: List<String>,
    ) {
        appendLine("$side QUIC trace (${captured.size} most recent events):")
        captured.forEach { appendLine("  $it") }
    }

    /**
     * A bounded most-recent-[MAX_EVENTS] ring over [events], CAS-appended because both sinks are written
     * from driver loops on arbitrary threads.
     */
    private fun ringSink(events: AtomicReference<List<String>>): TraceSink =
        TraceSink { event: TraceEvent ->
            // Truncated: a DGRAM line carries the full packet hex (~2400 chars for a 1200-byte
            // datagram), and hundreds of those would bury the report in CI. The prefix keeps everything
            // that localises a stall — timestamp, kind, direction, size, 4-tuple — and STATE /
            // PATH_STATE lines are far shorter than the cap, so they survive intact. Full payloads are a
            // replay concern, and replay wants a real per-connection sink, not this aggregate one.
            val rendered = event.toString()
            val line =
                if (rendered.length > MAX_LINE) rendered.take(MAX_LINE) + "…(+${rendered.length - MAX_LINE} chars)" else rendered
            while (true) {
                val current = events.load()
                val next = if (current.size >= MAX_EVENTS) current.subList(1, current.size) + line else current + line
                if (events.compareAndSet(current, next)) return@TraceSink
            }
        }

    private companion object {
        const val MAX_EVENTS = 250
        const val MAX_LINE = 180
    }
}
