package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.withQuicServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
