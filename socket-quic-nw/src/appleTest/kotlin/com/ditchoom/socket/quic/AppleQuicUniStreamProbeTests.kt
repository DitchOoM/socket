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

    /** The HTTP/3 client condition: the client OPENS its own uni streams AND accepts the server's. */
    @Test
    fun uniStream_clientOpensThenAccepts(): TestResult =
        runTest(timeout = 40.seconds) {
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
