package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-process HTTP/3 **loopback** suite: a real [withHttp3Connection] client talking to a minimal
 * HTTP/3 server ([Http3LoopbackServer]) we hand-roll from the same codecs, both ends over a real
 * [withQuicServer] / [withQuicConnection] QUIC connection on `localhost`. This is the deterministic
 * end-to-end exercise the scripted [Http3ConnectionTests] (FakeQuicScope) and the gated
 * [Http3PublicEndpointInteropTests] (real internet) can't be: it proves request → HEADERS/DATA →
 * half-close → response HEADERS/DATA → FIN over genuine QUIC streams, with no network dependency.
 *
 * It also seeds the HTTP/3 **server** role — [Http3LoopbackServer] is the first server-side
 * implementation (control stream + SETTINGS, uni-stream draining, request decode, canned response).
 *
 * Platform-parameterized exactly like `:socket-quic`'s `QuicServerTestSuite`: each platform that has
 * a working in-process QUIC server (JVM, linuxX64) subclasses this and supplies [testTlsConfig]
 * (cert/key paths) + [wrapTestBody] (skip when the native quiche binding is absent). JS has no QUIC
 * server, so it gets no subclass and runs nothing here.
 *
 * Lifecycle follows `QuicServerTestSuite`'s proven shape: the server accept loop runs in a launched
 * `serverJob`; a short settle lets [Http3LoopbackServer.serve] open its control stream and start
 * collecting before the client connects; and `finally { serverJob.cancel() }` plus `withQuicServer`'s
 * block-boundary teardown closes everything. The client always reads the full response body (and
 * `close()`s the response) before the block exits, so the response is fully delivered before teardown.
 */
abstract class Http3LoopbackTestSuite {
    /** Server cert + key for the in-process loopback server (platform-specific path resolution). */
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Skip-on-missing-native-lib hook; JVM overrides to translate `UnsatisfiedLinkError` to a skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /**
     * Wall-clock multiplier for every timeout/idle budget below, so a slow or jittery runner gets
     * proportionally more headroom without changing the suite's timing *relationships*. Default 1.0
     * (no change); platform subclasses override to read `QUIC_TEST_TIME_SCALE` (the same env var
     * `:socket-quic`'s `testTimeScale` uses — that helper is `internal` to that module, so the hook
     * is reproduced here rather than shared). The virtualized macos-26 CI runner has multi-second
     * scheduler stalls that tripped these (loopback-only) budgets while bare-metal passes.
     */
    protected open val timeScale: Double get() = 1.0

    /** Parse a `QUIC_TEST_TIME_SCALE` env value the same way `:socket-quic`'s `testTimeScale` does. */
    protected fun parseTimeScale(raw: String?): Double = raw?.trim()?.toDoubleOrNull()?.coerceIn(1.0, 10.0) ?: 1.0

    /** Scale a deadline by [timeScale]; `>= 1.0` so it only ever grants headroom. */
    protected fun Duration.scaled(): Duration = this * timeScale

    /**
     * HTTP/3 loopback test runner with a wall-clock timeout, mirroring `:socket-quic`'s `runQuicTest`:
     * [runTest] gives the right per-platform [TestResult] shape (Unit on JVM/K-N), and the body runs on
     * [Dispatchers.Default] so real QUIC I/O and real timing work (no virtual-time fast-forward).
     *
     * A member (not a top-level fn) so the outer backstop scales by [timeScale] in lock-step with the
     * inner per-op budgets — otherwise a scaled-up test would blow this cap before its own deadline.
     */
    private fun runHttp3LoopbackTest(
        timeout: Duration = 30.seconds.scaled(),
        block: suspend CoroutineScope.() -> Unit,
    ): TestResult =
        runTest(timeout = timeout + 15.seconds.scaled()) {
            withContext(Dispatchers.Default) {
                withTimeout(timeout) { block() }
            }
        }

    // Datagrams enabled so the WebTransport datagram tests (RFC 9297) work; harmless for the others
    // (it only advertises max_datagram_frame_size in the QUIC handshake).
    //
    // `.forHttp3()` (PreferStreams) is applied to match exactly what the production withHttp3Server /
    // withHttp3Connection entrypoints force at their boundary: on Apple's Network.framework, extracting
    // a datagram flow steals inbound/server-initiated stream delivery (the control stream's SETTINGS
    // never reach the peer), so HTTP/3 must prioritize streams. The client tests already route through
    // withHttp3Connection (which calls forHttp3 itself); the loopback SERVER here is built with a raw
    // withQuicServer, so without this it would run a transport config production never uses — and its
    // control-stream SETTINGS would silently not arrive on Apple. On quiche (JVM/Linux) PreferStreams is
    // a no-op for datagrams, so the datagram round-trip test is unaffected there.
    // keepAliveInterval keeps the connection alive through scheduler stalls on a loaded CI runner:
    // the QUIC stack (NW / quiche) sends PINGs on its own timer, independent of the Kotlin coroutine
    // dispatcher, so a starved test coroutine no longer lets the peer's idle timer expire mid-test
    // (the idle-close that surfaced as QuicCloseException). Kept small + UN-scaled (more frequent than
    // the idle timeout, never less) and well under idleTimeout as QuicOptions.validate requires.
    private val serverQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds.scaled(),
            keepAliveInterval = 2.seconds,
            datagrams = DatagramOptions(),
        ).forHttp3()

    private val clientQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds.scaled(),
            keepAliveInterval = 2.seconds,
            datagrams = DatagramOptions(),
        ).forHttp3()

    // QUIC stream I/O is zero-copy: it reads each buffer's native address. On Kotlin/Native,
    // BufferFactory.Default allocates heap (no native memory), so frame/body buffers MUST come from
    // a native-memory-backed factory (deterministic()) — the same choice QuicServerTestSuite and the
    // live interop test make. Both the client (withHttp3Connection's bootstrap + request frames) and
    // the server (Http3LoopbackServer) allocate through this.
    private val connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic())

    /** A native-memory (zero-copy-safe) buffer holding [s] as UTF-8, positioned for reading. */
    private fun textBuffer(s: String): PlatformBuffer =
        BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }

    @Test
    fun getRoundTripsThroughInProcessServer() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val server =
                    Http3LoopbackServer(connectionOptions) { request ->
                        assertEquals("GET", request.method)
                        assertEquals("/hello", request.path)
                        Http3LoopbackServer.Response(
                            status = 200,
                            headers = listOf(QpackHeaderField("content-type", "text/plain")),
                            body = "hello from h3 loopback",
                        )
                    }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100) // let serve() open its control stream + start collecting before the client connects
                    try {
                        val result =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                            ) {
                                val response = request(Http3Request(method = "GET", authority = "localhost", path = "/hello"))
                                try {
                                    val body = response.readFullBody()
                                    val text = body.readString(body.remaining(), Charset.UTF8)
                                    body.freeIfNeeded()
                                    Triple(response.status, response.headers, text)
                                } finally {
                                    response.close()
                                }
                            }
                        assertEquals(200, result.first)
                        assertEquals("hello from h3 loopback", result.third)
                        assertEquals("text/plain", result.second.firstOrNull { it.name == "content-type" }?.value)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun postEchoesRequestBodyThroughInProcessServer() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val server =
                    Http3LoopbackServer(connectionOptions) { request ->
                        // Echo the request body back with a 201 — exercises the request DATA path
                        // on the server read side and the response DATA path on the client read side.
                        Http3LoopbackServer.Response(status = 201, body = "echo:${request.body}")
                    }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100) // let serve() open its control stream + start collecting before the client connects
                    try {
                        val (status, text) =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                            ) {
                                val payload = "ping-body"
                                val bodyBuf = BufferFactory.deterministic().allocate(payload.length)
                                bodyBuf.writeString(payload, Charset.UTF8)
                                bodyBuf.resetForRead()
                                try {
                                    val response =
                                        request(
                                            Http3Request(method = "POST", authority = "localhost", path = "/echo", body = bodyBuf),
                                        )
                                    try {
                                        val body = response.readFullBody()
                                        val out = body.readString(body.remaining(), Charset.UTF8)
                                        body.freeIfNeeded()
                                        response.status to out
                                    } finally {
                                        response.close()
                                    }
                                } finally {
                                    // request() is zero-copy and does not take ownership of the body buffer.
                                    bodyBuf.freeIfNeeded()
                                }
                            }
                        assertEquals(201, status)
                        assertEquals("echo:ping-body", text)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun malformedFrameSequenceFromServer_abortsConnection() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // Server sends a DATA frame before the response HEADERS — an invalid frame sequence
                // (RFC 9114 §4.1). Over a real QUIC connection the client must detect it and abort
                // the connection with H3_FRAME_UNEXPECTED.
                val server =
                    Http3LoopbackServer(connectionOptions) {
                        Http3LoopbackServer.Response(status = 200, body = "unreachable", dataBeforeHeaders = true)
                    }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100)
                    try {
                        val code =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                            ) {
                                val e =
                                    assertFailsWith<Http3StreamException> {
                                        request(Http3Request(method = "GET", authority = "localhost", path = "/"))
                                    }
                                e.errorCode
                            }
                        assertEquals(Http3ErrorCode.FRAME_UNEXPECTED, code)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun malformedMessageFromServer_resetsStreamButConnectionSurvives() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // "/bad" → a HEADERS frame with no :status (a malformed message, RFC 9114 §4.1.2):
                // the client resets that one stream with H3_MESSAGE_ERROR but keeps the connection,
                // so a follow-up request to "/good" on the SAME connection still succeeds.
                val server =
                    Http3LoopbackServer(connectionOptions) { request ->
                        if (request.path == "/bad") {
                            Http3LoopbackServer.Response(status = 200, body = "x", omitStatus = true)
                        } else {
                            Http3LoopbackServer.Response(status = 200, body = "good-body")
                        }
                    }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100)
                    try {
                        val (badCode, secondStatus, secondBody) =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                            ) {
                                val bad =
                                    assertFailsWith<Http3StreamException> {
                                        request(Http3Request(method = "GET", authority = "localhost", path = "/bad"))
                                    }
                                // A stream-scoped reset must NOT have aborted the whole connection.
                                assertNull(connectionError, "malformed message must reset the stream, not close the connection")
                                val response = request(Http3Request(method = "GET", authority = "localhost", path = "/good"))
                                try {
                                    val body = response.readFullBody()
                                    val text = body.readString(body.remaining(), Charset.UTF8)
                                    body.freeIfNeeded()
                                    Triple(bad.errorCode, response.status, text)
                                } finally {
                                    response.close()
                                }
                            }
                        assertEquals(Http3ErrorCode.MESSAGE_ERROR, badCode)
                        assertEquals(200, secondStatus)
                        assertEquals("good-body", secondBody)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun dynamicQpackRoundTripsBothDirectionsAcrossRepeatedRequests() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // Both ends advertise a usable QPACK dynamic table, so requests AND responses are
                // dynamically compressed. Repeating a request with the same custom header (and getting
                // the same custom response header back) drives entries into both dynamic tables and then
                // references them on later requests — exercising encoder + decoder, both directions, over
                // real QUIC. Decoding correctly across the repeats is the proof the dynamic path works.
                val server =
                    Http3LoopbackServer(connectionOptions, qpackCapacity = 4096) { request ->
                        val echoed = request.headers.firstOrNull { it.name == "x-client" }?.value
                        Http3LoopbackServer.Response(
                            status = 200,
                            headers = listOf(QpackHeaderField("x-server", "server-token")),
                            body = "echo:$echoed",
                        )
                    }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100)
                    try {
                        withHttp3Connection(
                            "localhost",
                            port,
                            quicOptions = clientQuicOptions,
                            connectionOptions = connectionOptions,
                            timeout = 15.seconds.scaled(),
                        ) {
                            // Let the peer SETTINGS arrive so the client's encoder activates before requests.
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            delay(50)
                            repeat(3) { i ->
                                val response =
                                    request(
                                        Http3Request(
                                            method = "GET",
                                            authority = "localhost",
                                            path = "/item-$i",
                                            headers = listOf(QpackHeaderField("x-client", "client-token")),
                                        ),
                                    )
                                try {
                                    val body = response.readFullBody()
                                    val text = body.readString(body.remaining(), Charset.UTF8)
                                    body.freeIfNeeded()
                                    assertEquals(200, response.status, "request $i status")
                                    assertEquals(
                                        "server-token",
                                        response.headers.firstOrNull { it.name == "x-server" }?.value,
                                        "request $i x-server",
                                    )
                                    assertEquals("echo:client-token", text, "request $i body (server decoded our x-client)")
                                } finally {
                                    response.close()
                                }
                            }
                            assertNull(connectionError, "dynamic QPACK exchange must not raise a connection error")
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun serverPushDeliversPromisedResponse() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // For GET /index.html the server promises + pushes /style.css. The client enables push
                // (maxPushId = 8) and consumes the pushed response off the `pushes` flow alongside the
                // main response — exercising MAX_PUSH_ID, PUSH_PROMISE on the request stream, the push
                // stream, and promise↔stream correlation, end-to-end over real QUIC.
                val server =
                    Http3LoopbackServer(
                        connectionOptions,
                        serverPushes = { request ->
                            if (request.path == "/index.html") {
                                listOf(
                                    Http3LoopbackServer.Push(
                                        authority = "localhost",
                                        path = "/style.css",
                                        response =
                                            Http3LoopbackServer.Response(
                                                status = 200,
                                                headers = listOf(QpackHeaderField("content-type", "text/css")),
                                                body = "body{color:red}",
                                            ),
                                    ),
                                )
                            } else {
                                emptyList()
                            }
                        },
                    ) { Http3LoopbackServer.Response(status = 200, body = "<html>") }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100)
                    try {
                        val result =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                                maxPushId = 8,
                            ) {
                                // Let our MAX_PUSH_ID reach the server before it handles the request, so it
                                // knows push is allowed (it decides per-request whether to push).
                                withTimeout(5.seconds.scaled()) { peerSettings() }
                                delay(100)
                                // Collect the first push concurrently — the promise arrives during request().
                                val pushDeferred = async { withTimeout(10.seconds.scaled()) { pushes.first() } }
                                val response = request(Http3Request(method = "GET", authority = "localhost", path = "/index.html"))
                                val mainBody = response.readFullBody()
                                val mainText = mainBody.readString(mainBody.remaining(), Charset.UTF8)
                                mainBody.freeIfNeeded()
                                response.close()

                                val push = pushDeferred.await()
                                val pushResponse = withTimeout(10.seconds.scaled()) { push.response() }
                                val pushBody = pushResponse.readFullBody()
                                val pushText = pushBody.readString(pushBody.remaining(), Charset.UTF8)
                                pushBody.freeIfNeeded()
                                val contentType = pushResponse.headers.firstOrNull { it.name == "content-type" }?.value
                                pushResponse.close()
                                assertNull(connectionError, "server push must not raise a connection error")
                                PushResult(mainText, push.promisedRequest.path, pushResponse.status, pushText, contentType)
                            }
                        assertEquals("<html>", result.mainBody)
                        assertEquals("/style.css", result.promisedPath)
                        assertEquals(200, result.pushStatus)
                        assertEquals("body{color:red}", result.pushBody)
                        assertEquals("text/css", result.pushContentType)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun productionServerInitiatedPush() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The PRODUCTION server (withHttp3Server) initiates a push: for GET /index.html its
                // handler calls exchange.push("/style.css") { … }, sending a PUSH_PROMISE on the request
                // stream + a push stream. The real client (push enabled) receives it on `pushes`.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    onRequest = {
                        if (request.path == "/index.html") {
                            push(path = "/style.css") {
                                val css = textBuffer("body{color:green}")
                                try {
                                    send(200, listOf(QpackHeaderField("content-type", "text/css")), css)
                                } finally {
                                    css.freeIfNeeded()
                                }
                            }
                        }
                        val html = textBuffer("<html>")
                        try {
                            response.send(200, body = html)
                        } finally {
                            html.freeIfNeeded()
                        }
                    },
                ) {
                    delay(100)
                    val result =
                        withHttp3Connection(
                            "localhost",
                            port,
                            quicOptions = clientQuicOptions,
                            connectionOptions = connectionOptions,
                            timeout = 15.seconds.scaled(),
                            maxPushId = 8,
                        ) {
                            // Let our MAX_PUSH_ID reach the server before it handles the request.
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            delay(100)
                            val pushDeferred = async { withTimeout(10.seconds.scaled()) { pushes.first() } }
                            val r = request(Http3Request(method = "GET", authority = "localhost", path = "/index.html"))
                            val htmlBody = r.readFullBody()
                            val htmlText = htmlBody.readString(htmlBody.remaining(), Charset.UTF8)
                            htmlBody.freeIfNeeded()
                            r.close()

                            val push = pushDeferred.await()
                            val pushResponse = withTimeout(10.seconds.scaled()) { push.response() }
                            val pb = pushResponse.readFullBody()
                            val pText = pb.readString(pb.remaining(), Charset.UTF8)
                            pb.freeIfNeeded()
                            val ct = pushResponse.headers.firstOrNull { it.name == "content-type" }?.value
                            pushResponse.close()
                            assertNull(connectionError, "server-initiated push must not raise a connection error")
                            PushResult(htmlText, push.promisedRequest.path, pushResponse.status, pText, ct)
                        }
                    assertEquals("<html>", result.mainBody)
                    assertEquals("/style.css", result.promisedPath)
                    assertEquals(200, result.pushStatus)
                    assertEquals("body{color:green}", result.pushBody)
                    assertEquals("text/css", result.pushContentType)
                }
            }
        }

    @Test
    fun productionServerPushesMultipleConcurrently() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The handler pushes THREE resources for one request; since push bodies stream
                // concurrently (the handler isn't blocked on each), all three promises + responses must
                // still arrive. Proves the concurrent push-write path.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    onRequest = {
                        if (request.path == "/index.html") {
                            repeat(3) { i ->
                                push(path = "/asset-$i.css") {
                                    val b = textBuffer("asset-$i")
                                    try {
                                        send(200, body = b)
                                    } finally {
                                        b.freeIfNeeded()
                                    }
                                }
                            }
                        }
                        val html = textBuffer("<html>")
                        try {
                            response.send(200, body = html)
                        } finally {
                            html.freeIfNeeded()
                        }
                    },
                ) {
                    delay(100)
                    val paths =
                        withHttp3Connection(
                            "localhost",
                            port,
                            quicOptions = clientQuicOptions,
                            connectionOptions = connectionOptions,
                            timeout = 15.seconds.scaled(),
                            maxPushId = 8,
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            delay(100)
                            val collected = async { withTimeout(10.seconds.scaled()) { pushes.take(3).toList() } }
                            val r = request(Http3Request(method = "GET", authority = "localhost", path = "/index.html"))
                            r.readFullBody().freeIfNeeded()
                            r.close()
                            val received = collected.await()
                            received.forEach { p ->
                                val pr = withTimeout(10.seconds.scaled()) { p.response() }
                                pr.readFullBody().freeIfNeeded()
                                pr.close()
                            }
                            received.map { it.promisedRequest.path }.sorted()
                        }
                    assertEquals(listOf("/asset-0.css", "/asset-1.css", "/asset-2.css"), paths)
                }
            }
        }

    @Test
    fun serverPushWindowRollsViaReIssuedMaxPushId() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // maxPushId = 0 advertises room for exactly ONE push (id 0). The server pushes one
                // resource per request; the client must re-issue MAX_PUSH_ID as it observes push ids,
                // or the server runs out of credit after id 0. Receiving more than one push proves the
                // rolling-window re-issue (RFC 9114 §7.2.7) works end-to-end.
                val server =
                    Http3LoopbackServer(
                        connectionOptions,
                        serverPushes = { request ->
                            listOf(
                                Http3LoopbackServer.Push(
                                    authority = "localhost",
                                    path = "${request.path}.css",
                                    response = Http3LoopbackServer.Response(status = 200, body = "css"),
                                ),
                            )
                        },
                    ) { Http3LoopbackServer.Response(status = 200, body = "ok") }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100)
                    try {
                        val pushCount =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                                maxPushId = 0,
                            ) {
                                withTimeout(5.seconds.scaled()) { peerSettings() }
                                delay(50)
                                val received = mutableListOf<Long>()
                                val collector =
                                    launch {
                                        pushes.collect {
                                            received += it.pushId
                                            it.cancel()
                                        }
                                    }
                                // Drive requests until the window demonstrably rolls (>= 2 distinct pushes),
                                // waiting REACTIVELY for each push rather than on a fixed sleep budget: a slow
                                // CI runner can take well over 100ms to round-trip the re-issued MAX_PUSH_ID,
                                // which made the old `delay(120)` version flaky. A working re-issue yields one
                                // push per request and reaches 2 in a couple of iterations; a broken re-issue
                                // never gets a second push, so the iteration cap is hit and the assert below
                                // fails deterministically (instead of passing or hanging on timing).
                                var i = 0
                                while (received.size < 2 && i < 6) {
                                    val before = received.size
                                    val r = request(Http3Request(method = "GET", authority = "localhost", path = "/p$i"))
                                    r.readFullBody().freeIfNeeded()
                                    r.close()
                                    i++
                                    // Wait up to 3s for this request's push to be observed before the next one.
                                    withTimeoutOrNull(3.seconds.scaled()) { while (received.size == before) delay(20) }
                                }
                                collector.cancel()
                                received.size
                            }
                        // Without re-issue the server could push only id 0 (one push); > 1 proves the window rolled.
                        assertTrue(pushCount >= 2, "expected the push window to roll past the initial credit; got $pushCount pushes")
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun productionServerRole_getAndPostRoundTrip() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The PRODUCTION server role (`withHttp3Server` + Http3ServerConnection) answering a real
                // `withHttp3Connection` client: GET returns headers+body, POST echoes the request body via
                // the streaming Http3ServerRequest.readFullBody / Http3ServerResponse.send path.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    onRequest = {
                        when (request.method) {
                            "GET" -> {
                                val body = textBuffer("hi from the h3 server")
                                try {
                                    response.send(200, listOf(QpackHeaderField("content-type", "text/plain")), body)
                                } finally {
                                    body.freeIfNeeded()
                                }
                            }
                            "POST" -> {
                                val reqBody = request.readFullBody()
                                val echo = "echo:" + reqBody.readString(reqBody.remaining(), Charset.UTF8)
                                reqBody.freeIfNeeded()
                                val out = textBuffer(echo)
                                try {
                                    response.send(201, body = out)
                                } finally {
                                    out.freeIfNeeded()
                                }
                            }
                            else -> response.send(404)
                        }
                    },
                ) {
                    delay(100) // let the accept loop start before the client connects
                    val result =
                        withHttp3Connection("localhost", port, clientQuicOptions, connectionOptions, 15.seconds.scaled()) {
                            val g = request(Http3Request(method = "GET", authority = "localhost", path = "/hi"))
                            val gBody = g.readFullBody()
                            val gText = gBody.readString(gBody.remaining(), Charset.UTF8)
                            gBody.freeIfNeeded()
                            val gOut = Triple(g.status, gText, g.headers.firstOrNull { it.name == "content-type" }?.value)
                            g.close()

                            val reqBuf = textBuffer("server-echo-me")
                            val p =
                                try {
                                    request(Http3Request(method = "POST", authority = "localhost", path = "/echo", body = reqBuf))
                                } finally {
                                    reqBuf.freeIfNeeded()
                                }
                            val pBody = p.readFullBody()
                            val pText = pBody.readString(pBody.remaining(), Charset.UTF8)
                            pBody.freeIfNeeded()
                            val pStatus = p.status
                            p.close()
                            ServerRoleResult(gOut.first, gOut.second, gOut.third, pStatus, pText)
                        }
                    assertEquals(200, result.getStatus)
                    assertEquals("hi from the h3 server", result.getBody)
                    assertEquals("text/plain", result.getContentType)
                    assertEquals(201, result.postStatus)
                    assertEquals("echo:server-echo-me", result.postBody)
                }
            }
        }

    @Test
    fun productionServerRole_dynamicQpackRoundTrip() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The production server with dynamic QPACK (capacity 4096): it decodes the client's
                // dynamically-compressed request header and compresses its own response header back,
                // across repeated requests that drive entries into both dynamic tables.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    qpackCapacity = 4096,
                    onRequest = {
                        val echoed = request.headers.firstOrNull { it.name == "x-client" }?.value
                        val out = textBuffer("echo:$echoed")
                        try {
                            response.send(200, listOf(QpackHeaderField("x-server", "server-token")), out)
                        } finally {
                            out.freeIfNeeded()
                        }
                    },
                ) {
                    delay(100)
                    withHttp3Connection("localhost", port, clientQuicOptions, connectionOptions, 15.seconds.scaled()) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        delay(50)
                        repeat(3) { i ->
                            val response =
                                request(
                                    Http3Request(
                                        method = "GET",
                                        authority = "localhost",
                                        path = "/item-$i",
                                        headers = listOf(QpackHeaderField("x-client", "client-token")),
                                    ),
                                )
                            try {
                                val body = response.readFullBody()
                                val text = body.readString(body.remaining(), Charset.UTF8)
                                body.freeIfNeeded()
                                assertEquals(200, response.status, "request $i status")
                                assertEquals(
                                    "server-token",
                                    response.headers.firstOrNull { it.name == "x-server" }?.value,
                                    "request $i x-server",
                                )
                                assertEquals("echo:client-token", text, "request $i body")
                            } finally {
                                response.close()
                            }
                        }
                        assertNull(connectionError, "production server dynamic-QPACK exchange must not raise a connection error")
                    }
                }
            }
        }

    @Test
    fun clientReceivesServerSettings() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The server opens its control stream + SETTINGS first thing; assert the client's
                // peer-stream router decodes them. Validates the server→client control direction.
                val server = Http3LoopbackServer(connectionOptions) { Http3LoopbackServer.Response(status = 200, body = "ok") }

                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverQuicOptions) {
                    val serverJob = launch { connections { server.serve(this) } }
                    delay(100) // let serve() open its control stream + start collecting before the client connects
                    try {
                        val settings =
                            withHttp3Connection(
                                "localhost",
                                port,
                                quicOptions = clientQuicOptions,
                                connectionOptions = connectionOptions,
                                timeout = 15.seconds.scaled(),
                            ) {
                                withTimeout(5.seconds.scaled()) { peerSettings() }
                            }
                        // Our server advertises a static-table-only QPACK config (capacity 0).
                        assertEquals(0L, settings.qpackMaxTableCapacity)
                        assertEquals(0L, settings.qpackBlockedStreams)
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    // --- WebTransport (RFC 9220 Extended CONNECT) — Phase 1: session establishment ---

    @Test
    fun webTransport_establishSession_happyPath() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // A real withHttp3Server accepting an Extended CONNECT, and a real withHttp3Connection
                // opening a WebTransport session: the client's session id must equal the id the server
                // saw, proving the CONNECT stream id is the shared session id (draft-ietf-webtrans-http3).
                val serverSawSession = CompletableDeferred<Long>()
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        assertEquals("localhost", authority)
                        assertEquals("/wt", path)
                        serverSawSession.complete(accept().sessionId)
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val clientSessionId =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            assertFalse(session.isClosed)
                            session.sessionId
                        }
                    // Awaited OUTSIDE the client block (never block on a server signal from inside it).
                    val serverId = withTimeout(5.seconds.scaled()) { serverSawSession.await() }
                    assertEquals(clientSessionId, serverId)
                }
            }
        }

    @Test
    fun webTransport_serverRejects_throwsWithStatus() =
        runHttp3LoopbackTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = { reject(403) },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val ex =
                            assertFailsWith<WebTransportException> {
                                connectWebTransport(authority = "localhost", path = "/nope")
                            }
                        assertTrue(ex.message!!.contains("403"), "message should carry the reject status: ${ex.message}")
                    }
                }
            }
        }

    @Test
    fun webTransport_peerWithoutSupport_throwsBeforeOpening() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // Server does NOT advertise WebTransport (no webTransport option) — the client must
                // fail the gate on peerSettings().webTransportSupported before opening a CONNECT stream.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 1),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        assertFailsWith<WebTransportException> {
                            connectWebTransport(authority = "localhost", path = "/x")
                        }
                    }
                }
            }
        }

    @Test
    fun webTransport_twoConcurrentSessions_getDistinctIds() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val serverIds = Channel<Long>(Channel.UNLIMITED)
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = { serverIds.trySend(accept().sessionId) },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val (a, b) =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val s1 = connectWebTransport(authority = "localhost", path = "/a")
                            val s2 = connectWebTransport(authority = "localhost", path = "/b")
                            s1.sessionId to s2.sessionId
                        }
                    assertNotEquals(a, b, "two sessions on one connection must have distinct ids")
                    val seen = withTimeout(5.seconds.scaled()) { setOf(serverIds.receive(), serverIds.receive()) }
                    assertEquals(setOf(a, b), seen)
                }
            }
        }

    // --- WebTransport Phase 2: streams (draft-ietf-webtrans-http3 §4.1 / §4.2) ---

    @Test
    fun webTransport_clientOpensBidiStream_serverEchoes() =
        runHttp3LoopbackTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        // Echo the first peer-opened bidirectional stream back with a prefix, then FIN.
                        val stream = session.incomingBidiStreams.first()
                        val msg = stream.readUtf8()
                        stream.write(textBuffer("echo:$msg"))
                        stream.close()
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val reply =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            val stream = session.openBidiStream()
                            stream.write(textBuffer("hello"))
                            stream.shutdownSend()
                            withTimeout(5.seconds.scaled()) { stream.readUtf8() }
                        }
                    assertEquals("echo:hello", reply)
                }
            }
        }

    @Test
    fun webTransport_clientOpensUniStream_serverReceives() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val serverGot = CompletableDeferred<String>()
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        val stream = session.incomingUniStreams.first()
                        serverGot.complete(stream.readUtf8())
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val session = connectWebTransport(authority = "localhost", path = "/wt")
                        val stream = session.openUniStream()
                        stream.write(textBuffer("uni-payload"))
                        stream.close()
                    }
                    assertEquals("uni-payload", withTimeout(5.seconds.scaled()) { serverGot.await() })
                }
            }
        }

    @Test
    fun webTransport_serverOpensUniStream_clientReceives() =
        runHttp3LoopbackTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        // Server-initiated unidirectional stream toward the client.
                        val stream = session.openUniStream()
                        stream.write(textBuffer("from-server"))
                        stream.close()
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val got =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            val stream = withTimeout(5.seconds.scaled()) { session.incomingUniStreams.first() }
                            withTimeout(5.seconds.scaled()) { stream.readUtf8() }
                        }
                    assertEquals("from-server", got)
                }
            }
        }

    // --- WebTransport Phase 3: datagrams (draft-ietf-webtrans-http3 §4.4, RFC 9297) ---

    // `open` so the Apple subclass can override-and-@Ignore it: on Network.framework's QUIC
    // connection-group API, extracting a datagram flow steals inbound *stream* delivery, so HTTP/3
    // (which needs inbound streams) forces PreferStreams and WebTransport datagrams are unavailable
    // there. Every other test in this suite is stream-based and runs on Apple unchanged.
    @Test
    open fun webTransport_datagramRoundTrip() =
        runHttp3LoopbackTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        val datagram = session.datagrams.first()
                        val text = datagram.readString(datagram.remaining(), Charset.UTF8)
                        datagram.freeIfNeeded()
                        session.sendDatagram(textBuffer("pong:$text"))
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val reply =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            session.sendDatagram(textBuffer("ping"))
                            val datagram = withTimeout(5.seconds.scaled()) { session.datagrams.first() }
                            val text = datagram.readString(datagram.remaining(), Charset.UTF8)
                            datagram.freeIfNeeded()
                            text
                        }
                    assertEquals("pong:ping", reply)
                }
            }
        }

    // --- WebTransport Phase 4: graceful close via WT_CLOSE_SESSION capsule (draft §6) ---

    @Test
    fun webTransport_clientCloses_serverObservesCodeAndReason() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val serverClose = CompletableDeferred<WebTransportCloseInfo>()
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        serverClose.complete(session.awaitClosed())
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val session = connectWebTransport(authority = "localhost", path = "/wt")
                        session.close(code = 42, reason = "all done")
                        assertTrue(session.isClosed)
                        delay(300) // let the WT_CLOSE_SESSION capsule + FIN flush before teardown
                    }
                    assertEquals(WebTransportCloseInfo(42, "all done"), withTimeout(5.seconds.scaled()) { serverClose.await() })
                }
            }
        }

    @Test
    fun webTransport_serverCloses_clientObservesCodeAndReason() =
        runHttp3LoopbackTest {
            wrapTestBody {
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        session.close(code = 7, reason = "server bye")
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val info =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            // The client observes the peer's close reactively via its own session signal.
                            withTimeout(5.seconds.scaled()) { session.awaitClosed() }
                        }
                    assertEquals(WebTransportCloseInfo(7, "server bye"), info)
                }
            }
        }

    // --- WebTransport stream reset error-code mapping (draft-ietf-webtrans-http3 §4.3) ---

    @Test
    fun webTransport_streamReset_codeRoundTripsThroughHttp3Space() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // A code that straddles a §4.3 skip boundary, so a naive pass-through would not round-trip.
                val wtCode = 0x1e7u
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        val stream = session.incomingBidiStreams.first()
                        // Read the opener's first chunk, then abort the stream with a WebTransport code.
                        // reset() maps it into the HTTP/3 error-code space on the RESET_STREAM/STOP_SENDING.
                        withTimeout(5.seconds.scaled()) { stream.read() }
                        stream.reset(wtCode.toLong()) // Resettable.reset is the buffer-flow Long contract
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val observed =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            val stream = session.openBidiStream()
                            stream.write(textBuffer("hello"))
                            // Keep writing until the peer's STOP_SENDING surfaces; the WT layer decodes the
                            // HTTP/3 code back to the original WebTransport application error code.
                            withTimeout(5.seconds.scaled()) {
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
                        }
                    assertEquals(wtCode, observed)
                }
            }
        }

    // --- WebTransport WT_DRAIN_SESSION (draft-ietf-webtrans-http3 §5) ---

    @Test
    fun webTransport_serverDrains_clientObservesDrainThenBothCloseCleanly() =
        runHttp3LoopbackTest {
            wrapTestBody {
                val serverClose = CompletableDeferred<WebTransportCloseInfo>()
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        // Wind the session down without closing it, then wait for the client's clean close.
                        session.drain()
                        serverClose.complete(session.awaitClosed())
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val session = connectWebTransport(authority = "localhost", path = "/wt")
                        // The peer's drain is surfaced reactively; it must NOT close the session.
                        withTimeout(5.seconds.scaled()) { session.drained.first() }
                        assertTrue(session.isDrainRequested)
                        assertFalse(session.isClosed, "WT_DRAIN_SESSION must not close the session")
                        // Now finish cleanly from the client side.
                        session.close(code = 0, reason = "drained, closing")
                        assertTrue(session.isClosed)
                        delay(300) // let the WT_CLOSE_SESSION capsule + FIN flush before teardown
                    }
                    assertEquals(
                        WebTransportCloseInfo(0, "drained, closing"),
                        withTimeout(5.seconds.scaled()) { serverClose.await() },
                    )
                }
            }
        }

    @Test
    fun webTransport_clientDrains_serverObservesDrain() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The mirror of the server-drain case: drain() and the drained signal are role-agnostic
                // (the shared mux), so a client-initiated drain must surface on the server while open.
                val serverObservedWhileOpen = CompletableDeferred<Boolean>()
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        withTimeout(5.seconds.scaled()) { session.drained.first() }
                        // Only the drain is in flight here (the client waits for our close before closing),
                        // so observing it while still open is deterministic. Then we finish the session.
                        serverObservedWhileOpen.complete(session.isDrainRequested && !session.isClosed)
                        session.close(code = 0, reason = "server done")
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val session = connectWebTransport(authority = "localhost", path = "/wt")
                        session.drain()
                        assertFalse(session.isClosed, "draining our own session must not close it")
                        // Let the server observe our drain and close back; we don't close first.
                        withTimeout(5.seconds.scaled()) { session.awaitClosed() }
                    }
                    assertTrue(
                        withTimeout(5.seconds.scaled()) { serverObservedWhileOpen.await() },
                        "server must observe the drain while the session is still open",
                    )
                }
            }
        }

    @Test
    fun webTransport_drainKeepsTheTransportUsable() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // draft §5: a drain only signals intent — the session stays open and streams keep working.
                // Enforcement (stop opening new streams) is the application's choice, not the transport's.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        session.drain()
                        // A stream still flows after the drain is sent.
                        val stream = session.incomingBidiStreams.first()
                        val msg = stream.readUtf8()
                        stream.write(textBuffer("echo:$msg"))
                        stream.close()
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    val reply =
                        withHttp3Connection(
                            "localhost",
                            port,
                            clientQuicOptions,
                            connectionOptions,
                            15.seconds.scaled(),
                            webTransport = WebTransportOptions(maxSessions = 4),
                        ) {
                            withTimeout(5.seconds.scaled()) { peerSettings() }
                            val session = connectWebTransport(authority = "localhost", path = "/wt")
                            withTimeout(5.seconds.scaled()) { session.drained.first() }
                            assertFalse(session.isClosed, "drain must not close the session")
                            // Open + round-trip a stream AFTER observing the drain.
                            val stream = session.openBidiStream()
                            stream.write(textBuffer("after-drain"))
                            stream.shutdownSend()
                            withTimeout(5.seconds.scaled()) { stream.readUtf8() }
                        }
                    assertEquals("echo:after-drain", reply)
                }
            }
        }

    @Test
    fun webTransport_drainedFlowCompletesWithoutEmitting_whenSessionClosesUndrained() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // The no-hang guarantee: a `drained` collector must terminate (emitting nothing) when the
                // session ends without ever being drained, rather than awaiting a drain that never comes.
                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    webTransport = WebTransportOptions(maxSessions = 4),
                    onWebTransport = {
                        val session = accept()
                        session.close(code = 0, reason = "no drain") // close without ever draining
                    },
                    onRequest = { response.send(404) },
                ) {
                    delay(100)
                    withHttp3Connection(
                        "localhost",
                        port,
                        clientQuicOptions,
                        connectionOptions,
                        15.seconds.scaled(),
                        webTransport = WebTransportOptions(maxSessions = 4),
                    ) {
                        withTimeout(5.seconds.scaled()) { peerSettings() }
                        val session = connectWebTransport(authority = "localhost", path = "/wt")
                        withTimeout(5.seconds.scaled()) { session.awaitClosed() }
                        // drainSignal completed `false` on close → the flow finishes with no element.
                        assertTrue(
                            withTimeout(5.seconds.scaled()) { session.drained.toList() }.isEmpty(),
                            "drained must complete without emitting when no drain ever arrived",
                        )
                        assertFalse(session.isDrainRequested)
                    }
                }
            }
        }

    // --- Composable server middleware (Http3RequestFilter + then) over the existing onRequest handler ---

    @Test
    fun requestFilters_composeAroundHandlerAndShortCircuit() =
        runHttp3LoopbackTest {
            wrapTestBody {
                // Two filters composed in front of the real handler, handed straight to onRequest:
                //  - `observe` is an AROUND filter — it records the path then calls the inner handler.
                //  - `requireToken` SHORT-CIRCUITS — no x-token header ⇒ it sends 401 and never calls next,
                //    so the handler doesn't run. With the header it delegates through.
                // Observations cross coroutine/thread boundaries, so they go through a Channel (thread-safe),
                // not a shared var. The wire-observed status/body alone prove the short-circuit.
                val observed = Channel<String>(Channel.UNLIMITED)
                val observe: Http3RequestFilter = { next ->
                    {
                        observed.trySend(request.path)
                        next(this)
                    }
                }
                val requireToken: Http3RequestFilter = { next ->
                    {
                        if (request.headers.any { it.name == "x-token" }) {
                            next(this)
                        } else {
                            response.send(401) // short-circuit: inner handler never runs
                        }
                    }
                }
                val handler: Http3RequestHandler = {
                    val body = textBuffer("ok:${request.path}")
                    try {
                        response.send(200, body = body)
                    } finally {
                        body.freeIfNeeded()
                    }
                }

                withHttp3Server(
                    port = 0,
                    tlsConfig = testTlsConfig(),
                    quicOptions = serverQuicOptions,
                    connectionOptions = connectionOptions,
                    onRequest = observe.then(requireToken).then(handler),
                ) {
                    delay(100)
                    val result =
                        withHttp3Connection("localhost", port, clientQuicOptions, connectionOptions, 15.seconds.scaled()) {
                            // 1) with the token → passes both filters → handler → 200 + body.
                            val ok =
                                request(
                                    Http3Request(
                                        method = "GET",
                                        authority = "localhost",
                                        path = "/ok",
                                        headers = listOf(QpackHeaderField("x-token", "secret")),
                                    ),
                                )
                            val okBody = ok.readFullBody()
                            val okText = okBody.readString(okBody.remaining(), Charset.UTF8)
                            okBody.freeIfNeeded()
                            val okStatus = ok.status
                            ok.close()
                            // 2) without the token → auth filter short-circuits → 401, handler skipped.
                            val denied = request(Http3Request(method = "GET", authority = "localhost", path = "/denied"))
                            denied.readFullBody().freeIfNeeded()
                            val deniedStatus = denied.status
                            denied.close()
                            Triple(okStatus, okText, deniedStatus)
                        }
                    assertEquals(200, result.first, "authed request reached the handler")
                    assertEquals("ok:/ok", result.second)
                    assertEquals(401, result.third, "auth filter short-circuited the missing-token request")
                    // The AROUND filter ran for BOTH requests (even the short-circuited one, since it wraps
                    // the auth filter) — proving composition order outer→inner.
                    val seen =
                        setOf(
                            withTimeout(2.seconds.scaled()) { observed.receive() },
                            withTimeout(2.seconds.scaled()) { observed.receive() },
                        )
                    assertEquals(setOf("/ok", "/denied"), seen, "observe filter saw both requests")
                }
            }
        }
}

/** Read a bidirectional WebTransport stream to end-of-stream as a UTF-8 string. */
private suspend fun WebTransportStream.readUtf8(): String = drainUtf8 { read() }

/** Read a unidirectional (receive) WebTransport stream to end-of-stream as a UTF-8 string. */
private suspend fun WebTransportReceiveStream.readUtf8(): String = drainUtf8 { read() }

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

/** Result holder for the production server-role test (the block returns GET + POST results). */
private data class ServerRoleResult(
    val getStatus: Int,
    val getBody: String,
    val getContentType: String?,
    val postStatus: Int,
    val postBody: String,
)

/** Result holder for the server-push test (its withHttp3Connection block returns several values). */
private data class PushResult(
    val mainBody: String,
    val promisedPath: String,
    val pushStatus: Int,
    val pushBody: String,
    val pushContentType: String?,
)
