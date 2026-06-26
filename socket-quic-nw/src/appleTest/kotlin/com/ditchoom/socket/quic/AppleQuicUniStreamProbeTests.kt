@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for peer-initiated stream delivery + directionality over Network.framework — the
 * QUIC layer underneath HTTP/3 / WebTransport. Added when the Apple WebTransport suite first ran and
 * failed: the NW accept paths synthesized stream ids that always read as *bidirectional*, so a peer's
 * unidirectional control/SETTINGS stream was mislabeled and the HTTP/3 router never parsed it. The fix
 * reads the REAL QUIC stream id ([nw_helper_quic_stream_real_id]) on a now-live flow (after the first
 * read peek), in both the server and client accept paths.
 *
 * These all pass WITHOUT datagrams. NB (documented in V6_PHASE4_HANDOFF.md): with QUIC *datagrams*
 * enabled, extracting the NW datagram flow makes NW deliver ALL inbound data — including inbound stream
 * bytes — onto that datagram flow, so the group's new-connection handler stops delivering inbound
 * streams. Datagrams and inbound streams cannot coexist in NW's connection-group model; that's why the
 * WebTransport suite (which enables datagrams) is still red on Apple even though streams work here.
 */
class AppleQuicUniStreamProbeTests {
    private val opts =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun buf(s: String) =
        BufferFactory.deterministic().allocate(s.length).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }

    /** Server accept side: complete [received] with "<dir>:<text>" or a TIMEOUT sentinel. */
    private suspend fun CoroutineScope.serveOne(
        received: CompletableDeferred<String>,
        body: suspend QuicScope.(port: Int) -> Unit,
    ) {
        withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
            val serverJob =
                launch {
                    connections {
                        val stream = withTimeoutOrNull(6.seconds) { acceptStream() }
                        if (stream == null) {
                            received.complete("TIMEOUT_no_peer_stream")
                            return@connections
                        }
                        val dir = if (stream.streamId.isUnidirectional) "uni" else "bidi"
                        val data = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                        val text = if (data is ReadResult.Data) data.buffer.readString(data.buffer.remaining(), Charset.UTF8) else "no_data"
                        received.complete("$dir:$text")
                    }
                }
            delay(100)
            val clientJob = launch { withQuicConnection("localhost", port, opts, timeout = 10.seconds) { body(port) } }
            try {
                withTimeout(15.seconds) { received.await() }
            } finally {
                clientJob.cancel()
                serverJob.cancel()
            }
        }
    }

    @Test
    fun bidiStream_isDelivered_sanity(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = CompletableDeferred<String>()
                serveOne(received) {
                    val s = openStream()
                    s.write(buf("probe-bidi"), 5.seconds)
                    delay(8.seconds)
                }
                assertEquals("bidi:probe-bidi", received.await())
            }
        }

    @Test
    fun uniStream_noFin_isDelivered(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = CompletableDeferred<String>()
                serveOne(received) {
                    val s = openUniStream()
                    s.write(buf("probe-uni-nofin"), 5.seconds)
                    delay(8.seconds)
                }
                assertEquals("uni:probe-uni-nofin", received.await())
            }
        }

    @Test
    fun uniStream_withFin_isDelivered(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = CompletableDeferred<String>()
                serveOne(received) {
                    val s = openUniStream()
                    s.write(buf("probe-uni-fin"), 5.seconds)
                    s.close()
                    delay(8.seconds)
                }
                assertEquals("uni:probe-uni-fin", received.await())
            }
        }

    /**
     * The reverse direction — the one HTTP/3 depends on for the server's control stream + SETTINGS:
     * the SERVER opens a uni stream and the CLIENT accepts it (and must classify it as unidirectional).
     */
    @Test
    fun uniStream_serverToClient_isDelivered(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = CompletableDeferred<String>()
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                val s = openUniStream()
                                s.write(buf("srv-uni"), 5.seconds)
                                delay(8.seconds)
                            }
                        }
                    delay(100)
                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, opts, timeout = 10.seconds) {
                                val stream = withTimeoutOrNull(6.seconds) { acceptStream() }
                                if (stream == null) {
                                    received.complete("TIMEOUT_no_peer_stream")
                                    return@withQuicConnection
                                }
                                val dir = if (stream.streamId.isUnidirectional) "uni" else "bidi"
                                val data = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                                val text =
                                    if (data is ReadResult.Data) {
                                        data.buffer.readString(
                                            data.buffer.remaining(),
                                            Charset.UTF8,
                                        )
                                    } else {
                                        "no_data"
                                    }
                                received.complete("$dir:$text")
                            }
                        }
                    try {
                        withTimeout(15.seconds) { received.await() }
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
                assertEquals("uni:srv-uni", received.await())
            }
        }

    /**
     * The HTTP/3 SERVER condition that the single-[acceptStream] probes above never exercised: the client
     * opens MULTIPLE concurrent peer streams (3 uni like control+QPACK enc/dec, then 1 bidi like a request),
     * and the server must receive and correctly classify ALL of them via the [QuicScope.streams] flow —
     * exactly what `Http3ServerConnection.serve` does. If NW only delivers a subset, or mis-ids the bidi,
     * the H3 server never routes the request → onRequest never fires (the Apple WebTransport/H3 blocker).
     */
    @Test
    fun multipleConcurrentInboundStreams_serverReceivesAndClassifiesAll(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val results = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                streams().collect { stream ->
                                    launch {
                                        val dir = if (stream.streamId.isUnidirectional) "uni" else "bidi"
                                        val rawId = stream.streamId.id
                                        val data = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                                        val text =
                                            if (data is ReadResult.Data) {
                                                data.buffer.readString(data.buffer.remaining(), Charset.UTF8)
                                            } else {
                                                "no_data"
                                            }
                                        // id kept in the label so a misclassification shows the wire id in the failure.
                                        results.trySend("$dir(id=$rawId):$text")
                                    }
                                }
                            }
                        }
                    delay(100)
                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, opts, timeout = 10.seconds) {
                                // Mirror the HTTP/3 client bootstrap order: 3 uni streams, then a bidi request.
                                openUniStream().write(buf("control"), 5.seconds)
                                openUniStream().write(buf("qpack-enc"), 5.seconds)
                                openUniStream().write(buf("qpack-dec"), 5.seconds)
                                openStream().write(buf("request"), 5.seconds)
                                delay(10.seconds)
                            }
                        }
                    try {
                        val collected = mutableListOf<String>()
                        withTimeoutOrNull(15.seconds) {
                            repeat(4) { collected.add(results.receive()) }
                        }
                        // The bidi request is the one the H3 server must route to onRequest.
                        assertEquals(
                            1,
                            collected.count {
                                it.endsWith(":request") && it.startsWith("bidi")
                            },
                            "server must classify the request stream as bidi; got ${collected.sorted()}",
                        )
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The WebTransport timing the burst probe above doesn't cover: a bidi stream opened LATE — after the
     * H3 uni streams AND a first bidi round-trip have already completed — exactly like a WebTransport bidi
     * stream opened mid-session after CONNECT. The server's [QuicScope.streams] must still deliver it. If
     * NW stops delivering peer streams once the connection has been quiescent, this is the next blocker.
     */
    @Test
    fun lateBidiStream_afterRoundTrip_isStillDelivered(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                streams().collect { stream ->
                                    launch {
                                        val dir = if (stream.streamId.isUnidirectional) "uni" else "bidi"
                                        val data = withTimeoutOrNull(5.seconds) { stream.read(5.seconds) }
                                        val text =
                                            if (data is ReadResult.Data) {
                                                data.buffer.readString(
                                                    data.buffer.remaining(),
                                                    Charset.UTF8,
                                                )
                                            } else {
                                                "no_data"
                                            }
                                        received.trySend("$dir:$text")
                                        // Echo back on bidi streams so the client's round-trip completes.
                                        if (!stream.streamId.isUnidirectional) {
                                            stream.write(buf("echo:$text"), 5.seconds)
                                        }
                                    }
                                }
                            }
                        }
                    delay(100)
                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, opts, timeout = 10.seconds) {
                                // H3-like bootstrap: 3 uni streams.
                                openUniStream().write(buf("control"), 5.seconds)
                                openUniStream().write(buf("qpack-enc"), 5.seconds)
                                openUniStream().write(buf("qpack-dec"), 5.seconds)
                                // Bidi #1 (like the CONNECT): full round-trip.
                                val first = openStream()
                                first.write(buf("first"), 5.seconds)
                                withTimeoutOrNull(5.seconds) { first.read(5.seconds) }
                                // Bidi #2 (like a WebTransport bidi opened mid-session): the one under test.
                                openStream().write(buf("second"), 5.seconds)
                                delay(10.seconds)
                            }
                        }
                    try {
                        val collected = mutableListOf<String>()
                        withTimeoutOrNull(15.seconds) {
                            while (collected.none { it == "bidi:second" }) collected.add(received.receive())
                        }
                        assertEquals(
                            1,
                            collected.count { it == "bidi:second" },
                            "server must receive the late bidi stream; got ${collected.sorted()}",
                        )
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * The exact WebTransport `roundTripBidi` shape: client opens a bidi, writes, HALF-CLOSES the send side
     * (shutdownSend → FIN) while keeping the read side open, and the server must still receive the stream
     * via [QuicScope.streams], read to End, and echo back. The WT bidi echo returns empty on Apple even
     * though session establishment works; if shutdownSend makes the server's first peek read see End/Reset,
     * the phantom filter drops the stream before delivery (gotStream=null in the WT diagnostic).
     */
    @Test
    fun bidiHalfClose_roundTrip_isDelivered(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val serverReceived = CompletableDeferred<String>()
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                streams().collect { stream ->
                                    launch {
                                        if (stream.streamId.isUnidirectional) return@launch
                                        // Drain to End (the client half-closed), then echo back.
                                        val sb = StringBuilder()
                                        while (true) {
                                            val r = withTimeoutOrNull(5.seconds) { stream.read(5.seconds) }
                                            if (r is ReadResult.Data) {
                                                sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                            } else {
                                                break
                                            }
                                        }
                                        serverReceived.complete(sb.toString())
                                        stream.write(buf("echo:$sb"), 5.seconds)
                                        stream.close()
                                    }
                                }
                            }
                        }
                    delay(100)
                    val clientReply = CompletableDeferred<String>()
                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, opts, timeout = 10.seconds) {
                                // Uni streams first, mirroring the H3 bootstrap before a WT bidi.
                                openUniStream().write(buf("control"), 5.seconds)
                                val stream = openStream()
                                stream.write(buf("payload"), 5.seconds)
                                (stream as com.ditchoom.buffer.flow.HalfCloseable).shutdownSend()
                                val sb = StringBuilder()
                                while (true) {
                                    val r = withTimeoutOrNull(5.seconds) { stream.read(5.seconds) }
                                    if (r is ReadResult.Data) sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8)) else break
                                }
                                clientReply.complete(sb.toString())
                            }
                        }
                    try {
                        val srv = withTimeoutOrNull(15.seconds) { serverReceived.await() }
                        val cli = withTimeoutOrNull(15.seconds) { clientReply.await() }
                        assertEquals("payload", srv, "server must receive the half-closed bidi stream's payload")
                        assertEquals("echo:payload", cli, "client must receive the echo on the half-closed bidi stream")
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    /** The HTTP/3 client condition: the client OPENS its own uni streams AND accepts the server's. */
    @Test
    fun uniStream_clientOpensThenAccepts(): TestResult =
        runTest(timeout = 40.seconds) {
            if (shouldSkipQuicHarnessOnSimulator()) return@runTest
            withContext(Dispatchers.Default) {
                val received = CompletableDeferred<String>()
                withQuicServer(port = 0, tlsConfig = appleQuicTestTlsConfig(), quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                val s = openUniStream()
                                s.write(buf("srv-uni"), 5.seconds)
                                delay(8.seconds)
                            }
                        }
                    delay(100)
                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, opts, timeout = 10.seconds) {
                                openUniStream().write(buf("cli-uni-1"), 5.seconds)
                                openUniStream().write(buf("cli-uni-2"), 5.seconds)
                                val stream = withTimeoutOrNull(6.seconds) { acceptStream() }
                                if (stream == null) {
                                    received.complete("TIMEOUT_no_peer_stream")
                                    return@withQuicConnection
                                }
                                val dir = if (stream.streamId.isUnidirectional) "uni" else "bidi"
                                val data = withTimeoutOrNull(3.seconds) { stream.read(3.seconds) }
                                val text =
                                    if (data is ReadResult.Data) {
                                        data.buffer.readString(
                                            data.buffer.remaining(),
                                            Charset.UTF8,
                                        )
                                    } else {
                                        "no_data"
                                    }
                                received.complete("$dir:$text")
                            }
                        }
                    try {
                        withTimeout(15.seconds) { received.await() }
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
                assertEquals("uni:srv-uni", received.await())
            }
        }
}
