@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.nwquic26.NWQuic26Bridge
import com.ditchoom.socket.quic.nwquic26.NWQuic26Conn
import com.ditchoom.socket.quic.nwquic26.NWQuic26Listener
import com.ditchoom.socket.quic.nwquic26.NWQuic26Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * PLAN step 3 (streams + ids): drives QUIC streams through the OS-26 `NetworkConnection<QUIC>` bridge —
 * client-opened bidirectional + unidirectional streams accepted on the server's `inboundStreams` path
 * with their REAL wire ids and directionality (no synthetic-id dance — the new API exposes
 * `streamID`/`directionality`/`initiator` directly), a bidi request→response round-trip with send-side
 * half-close (`endOfStream`), and a server-INITIATED stream delivered to the client's inbound path
 * (`serverInit=true`). Together these are the stream surface HTTP/3 + WebTransport multiplex over.
 *
 * macosArm64-only (the `NWQuic26` cinterop bindings are per-target); the server presents the W3C
 * `pinned` EC P-256 identity and the client pins its leaf hash (see [NWQuic26TestSupport]).
 */
class NWQuic26BridgeStreamTest {
    private data class Inbound(
        val stream: NWQuic26Stream,
        val id: Long,
        val isUni: Boolean,
        val serverInit: Boolean,
    )

    private data class ServerSaw(
        val id: Long,
        val isUni: Boolean,
        val serverInit: Boolean,
        val content: String,
    )

    private companion object {
        const val RESET_CODE: ULong = 42u
    }

    @Test
    fun clientStreams_bidiRoundTripAndUni_acceptedWithRealIds() =
        runBlocking {
            withTimeout(25.seconds) {
                val alpn = listOf("nwq26-stream-test")
                val bridge = NWQuic26Bridge()

                // Server: accept inbound streams; a per-connection processor reads each fully and — for
                // bidi — echoes "echo:<req>" with FIN. inboundStreams is serial, so the K/N handler only
                // enqueues (never blocks); the processor below does the suspending I/O.
                val inbound = Channel<Inbound>(Channel.UNLIMITED)
                val serverSaw = Channel<ServerSaw>(Channel.UNLIMITED)
                val serverProc =
                    launch {
                        for (inb in inbound) {
                            launch {
                                val content = inb.stream.readAllString()
                                serverSaw.send(ServerSaw(inb.id, inb.isUni, inb.serverInit, content))
                                if (!inb.isUni) inb.stream.sendAwait("echo:$content".encodeToByteArray(), endOfStream = true)
                            }
                        }
                    }

                val listener =
                    startEchoServer(bridge, alpn) { conn ->
                        conn.onInboundStream { stream, id, isUni, serverInit ->
                            inbound.trySend(Inbound(stream!!, id.toLong(), isUni, serverInit))
                        }
                    }
                val port = listener.first
                val client = connectClient(bridge, alpn, port)

                // Bidi: open, send "hello" + FIN, read the echoed response.
                val (bidi, bidiId) = client.openStreamAwait(uni = false)
                bidi.sendAwait("hello".encodeToByteArray(), endOfStream = true)
                val response = bidi.readAllString()

                // Uni: open, send "uni-data" + FIN (no response — send-only).
                val (uni, uniId) = client.openStreamAwait(uni = true)
                uni.sendAwait("uni-data".encodeToByteArray(), endOfStream = true)

                // Collect both server-side observations and key them by id.
                val saw = listOf(serverSaw.receive(), serverSaw.receive()).associateBy { it.id }

                assertEquals("echo:hello", response, "client should read the server's bidi echo")

                // Real RFC 9000 §2.1 wire ids: first client bidi = 0, first client uni = 2.
                assertEquals(0L, bidiId, "first client bidi stream id")
                assertEquals(2L, uniId, "first client uni stream id")

                val sawBidi = saw.getValue(bidiId)
                assertFalse(sawBidi.isUni, "bidi accepted as bidirectional")
                assertFalse(sawBidi.serverInit, "bidi is client-initiated")
                assertEquals("hello", sawBidi.content)

                val sawUni = saw.getValue(uniId)
                assertTrue(sawUni.isUni, "uni accepted as unidirectional")
                assertFalse(sawUni.serverInit, "uni is client-initiated")
                assertEquals("uni-data", sawUni.content)

                serverProc.cancel()
                client.closeWithAppErrorCode(0u)
                listener.second.cancel()
            }
        }

    @Test
    fun serverInitiatedStream_deliveredToClientInbound() =
        runBlocking {
            withTimeout(25.seconds) {
                val alpn = listOf("nwq26-srvstream-test")
                val bridge = NWQuic26Bridge()

                val serverConnReady = CompletableDeferred<NWQuic26Conn>()
                val listener =
                    startEchoServer(bridge, alpn) { conn ->
                        // Server must serve inbound streams to drive the handshake; we don't expect any
                        // here, but registering keeps the connection live + symmetric.
                        conn.onInboundStream { _, _, _, _ -> }
                        serverConnReady.complete(conn)
                    }
                val port = listener.first

                // Client: collect peer-initiated (server) streams.
                val clientInbound = Channel<Inbound>(Channel.UNLIMITED)
                val client =
                    connectClient(bridge, alpn, port) { c ->
                        c.onInboundStream { stream, id, isUni, serverInit ->
                            clientInbound.trySend(Inbound(stream!!, id.toLong(), isUni, serverInit))
                        }
                    }

                // Server opens a bidi stream to the client and pushes a message + FIN.
                val serverConn = serverConnReady.await()
                val (srvStream, srvId) = serverConn.openStreamAwait(uni = false)
                srvStream.sendAwait("srv-push".encodeToByteArray(), endOfStream = true)

                val got = clientInbound.receive()
                assertTrue(got.serverInit, "stream is server-initiated")
                assertFalse(got.isUni, "server opened a bidirectional stream")
                assertEquals(srvId, got.id, "client sees the server's real wire id")
                assertEquals("srv-push", got.stream.readAllString())

                client.closeWithAppErrorCode(0u)
                listener.second.cancel()
            }
        }

    @Test
    fun clientReset_observedByServerAsResetCode() =
        runBlocking {
            withTimeout(25.seconds) {
                val alpn = listOf("nwq26-reset-test")
                val bridge = NWQuic26Bridge()

                val inbound = Channel<Inbound>(Channel.UNLIMITED)
                val listener =
                    startEchoServer(bridge, alpn) { conn ->
                        conn.onInboundStream { stream, id, isUni, serverInit ->
                            inbound.trySend(Inbound(stream!!, id.toLong(), isUni, serverInit))
                        }
                    }
                val port = listener.first
                val client = connectClient(bridge, alpn, port)

                // Open a bidi stream and send a chunk WITHOUT FIN (so the stream stays open), then abort it.
                val (bidi, _) = client.openStreamAwait(uni = false)
                bidi.sendAwait("partial".encodeToByteArray(), endOfStream = false)

                val inb = inbound.receive()
                val first = inb.stream.receiveOnce()
                assertEquals("partial", first.first?.decodeToString(), "server reads the pre-reset chunk")

                bidi.resetWithAppErrorCode(RESET_CODE)

                // The next server read terminates with the peer's RESET_STREAM application error code.
                val afterReset = inb.stream.receiveOnce()
                assertEquals(RESET_CODE, afterReset.third, "server observes the client's RESET_STREAM app code")

                client.closeWithAppErrorCode(0u)
                listener.second.cancel()
            }
        }

    // --- shared setup ---

    /** Start a stream-only (no datagram) QUIC server presenting the `pinned` identity; returns (port, listener). */
    private suspend fun startEchoServer(
        bridge: NWQuic26Bridge,
        alpn: List<String>,
        onConnection: (NWQuic26Conn) -> Unit,
    ): Pair<Int, NWQuic26Listener> {
        val serverPort = CompletableDeferred<Int>()
        val listener =
            bridge.listenWithHost(
                host = "127.0.0.1",
                port = 0u,
                alpn = alpn,
                p12Path = testCertPath("pinned.p12"),
                p12Password = "testpass",
                idleTimeoutMs = 30_000,
                maxDatagramFrameSize = 0,
                keepAliveMs = 0,
                onConnection = { conn -> onConnection(conn!!) },
                onListenerState = { errCode, boundPort, desc ->
                    if (errCode == 0) {
                        serverPort.complete(boundPort.toInt())
                    } else {
                        serverPort.completeExceptionally(IllegalStateException("listener failed: $errCode ${desc ?: ""}"))
                    }
                },
            )
        return serverPort.await() to listener
    }

    /** Connect a pinning client to [port], awaiting `.ready`; [onConn] runs before connect to wire inbound handlers. */
    private suspend fun connectClient(
        bridge: NWQuic26Bridge,
        alpn: List<String>,
        port: Int,
        onConn: (NWQuic26Conn) -> Unit = {},
    ): NWQuic26Conn {
        val clientReady = CompletableDeferred<Unit>()
        var ref: NWQuic26Conn? = null
        val client =
            bridge.connectWithHost(
                host = "127.0.0.1",
                port = port.toUShort(),
                alpn = alpn,
                idleTimeoutMs = 30_000,
                maxDatagramFrameSize = 0,
                keepAliveMs = 0,
                serverCertificateHashes = listOf(pinFor("pinned")),
                requireChain = false,
                verifyPeer = true,
                onReady = { errCode, desc ->
                    if (errCode == 0) {
                        clientReady.complete(Unit)
                    } else {
                        clientReady.completeExceptionally(
                            IllegalStateException("connect failed: $errCode ${desc ?: ""} pinReason=${ref?.pinFailureReason()}"),
                        )
                    }
                },
            )
        ref = client
        onConn(client)
        clientReady.await()
        return client
    }
}
