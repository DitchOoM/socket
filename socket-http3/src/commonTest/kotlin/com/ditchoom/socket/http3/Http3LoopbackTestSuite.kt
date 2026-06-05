package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private val serverQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private val clientQuicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    // QUIC stream I/O is zero-copy: it reads each buffer's native address. On Kotlin/Native,
    // BufferFactory.Default allocates heap (no native memory), so frame/body buffers MUST come from
    // a native-memory-backed factory (deterministic()) — the same choice QuicServerTestSuite and the
    // live interop test make. Both the client (withHttp3Connection's bootstrap + request frames) and
    // the server (Http3LoopbackServer) allocate through this.
    private val connectionOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

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
                                timeout = 15.seconds,
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
                                timeout = 15.seconds,
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
                                timeout = 15.seconds,
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
                                timeout = 15.seconds,
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
                            timeout = 15.seconds,
                        ) {
                            // Let the peer SETTINGS arrive so the client's encoder activates before requests.
                            withTimeout(5.seconds) { peerSettings() }
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
                                timeout = 15.seconds,
                                maxPushId = 8,
                            ) {
                                // Let our MAX_PUSH_ID reach the server before it handles the request, so it
                                // knows push is allowed (it decides per-request whether to push).
                                withTimeout(5.seconds) { peerSettings() }
                                delay(100)
                                // Collect the first push concurrently — the promise arrives during request().
                                val pushDeferred = async { withTimeout(10.seconds) { pushes.first() } }
                                val response = request(Http3Request(method = "GET", authority = "localhost", path = "/index.html"))
                                val mainBody = response.readFullBody()
                                val mainText = mainBody.readString(mainBody.remaining(), Charset.UTF8)
                                mainBody.freeIfNeeded()
                                response.close()

                                val push = pushDeferred.await()
                                val pushResponse = withTimeout(10.seconds) { push.response() }
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
                            timeout = 15.seconds,
                            maxPushId = 8,
                        ) {
                            // Let our MAX_PUSH_ID reach the server before it handles the request.
                            withTimeout(5.seconds) { peerSettings() }
                            delay(100)
                            val pushDeferred = async { withTimeout(10.seconds) { pushes.first() } }
                            val r = request(Http3Request(method = "GET", authority = "localhost", path = "/index.html"))
                            val htmlBody = r.readFullBody()
                            val htmlText = htmlBody.readString(htmlBody.remaining(), Charset.UTF8)
                            htmlBody.freeIfNeeded()
                            r.close()

                            val push = pushDeferred.await()
                            val pushResponse = withTimeout(10.seconds) { push.response() }
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
                                timeout = 15.seconds,
                                maxPushId = 0,
                            ) {
                                withTimeout(5.seconds) { peerSettings() }
                                delay(50)
                                val received = mutableListOf<Long>()
                                val collector =
                                    launch {
                                        pushes.collect {
                                            received += it.pushId
                                            it.cancel()
                                        }
                                    }
                                repeat(3) { i ->
                                    val r = request(Http3Request(method = "GET", authority = "localhost", path = "/p$i"))
                                    r.readFullBody().freeIfNeeded()
                                    r.close()
                                    delay(120) // let the re-issued MAX_PUSH_ID reach the server before the next request
                                }
                                delay(200)
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
                        withHttp3Connection("localhost", port, clientQuicOptions, connectionOptions, 15.seconds) {
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
                    withHttp3Connection("localhost", port, clientQuicOptions, connectionOptions, 15.seconds) {
                        withTimeout(5.seconds) { peerSettings() }
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
                                timeout = 15.seconds,
                            ) {
                                withTimeout(5.seconds) { peerSettings() }
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

/**
 * HTTP/3 loopback test runner with a wall-clock timeout, mirroring `:socket-quic`'s `runQuicTest`:
 * [runTest] gives the right per-platform [TestResult] shape (Unit on JVM/K-N), and the body runs on
 * [Dispatchers.Default] so real QUIC I/O and real timing work (no virtual-time fast-forward).
 */
private fun runHttp3LoopbackTest(
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestResult =
    runTest(timeout = timeout + 15.seconds) {
        withContext(Dispatchers.Default) {
            withTimeout(timeout) { block() }
        }
    }
