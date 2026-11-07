@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicEarlyDataOutcome
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPortBinding
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicSessionTicket
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.withQuicConnection
import com.ditchoom.socket.quic.withQuicServer
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP/3 requests that arrive as 0-RTT (RFC 8470, RFC 9114 §10.9): a replay-safe request is answered
 * before the handshake completes, any other is held until it is confirmed and then answered normally.
 *
 * Every request here is written in the client's first flight while [HandshakeDropChannel] drops the
 * client's Handshake packets, so the server has the request but cannot complete its handshake until the
 * test lets the Finished through. Whether a request ran early is read from the server's handler: it
 * records whether the Finished was still being dropped when it ran.
 */
class JvmHttp3EarlyDataTest {
    private data class Handled(
        val method: String,
        val path: String,
        val beforeConfirmation: Boolean,
    )

    @Test
    fun anEarlyGetIsAnsweredBeforeTheHandshakeCompletes() =
        earlyDataTest(Http3ReplaySafety.SafeMethods) { port, ticket, channel, handled ->
            val status =
                earlyRequests(port, ticket, listOf(Request("GET", "/index"))) { streams ->
                    val status = readStatus(streams.single())
                    assertTrue(channel.dropping && channel.dropped > 0, "the response arrived while the Finished was dropped")
                    channel.release()
                    status
                }
            assertEquals(200, status)
            assertEquals(Handled("GET", "/index", beforeConfirmation = true), withTimeout(STEP) { handled.receive() })
        }

    @Test
    fun anEarlyPostIsHeldUntilTheHandshakeIsConfirmedThenAnswered() =
        earlyDataTest(Http3ReplaySafety.SafeMethods) { port, ticket, channel, handled ->
            val statuses =
                earlyRequests(port, ticket, listOf(Request("POST", "/submit"), Request("GET", "/index"))) { streams ->
                    // The GET, written after the POST, is answered early: the server is serving early requests.
                    assertEquals(200, readStatus(streams[1]))
                    assertEquals(Handled("GET", "/index", beforeConfirmation = true), withTimeout(STEP) { handled.receive() })
                    assertTrue(handled.isEmpty, "the POST ran before the handshake was confirmed: ${handled.tryReceive()}")
                    channel.release()
                    readStatus(streams[0])
                }
            assertEquals(200, statuses, "the held POST is answered normally once the handshake is confirmed — never 425")
            assertEquals(Handled("POST", "/submit", beforeConfirmation = false), withTimeout(STEP) { handled.receive() })
        }

    @Test
    fun replaySafetyOverridesTheMethodInBothDirections() =
        earlyDataTest(
            Http3ReplaySafety { request ->
                request.path != "/logout" &&
                    (Http3ReplaySafety.SafeMethods.isReplaySafe(request) || request.headers.any { it.name == "idempotency-key" })
            },
        ) { port, ticket, channel, handled ->
            val logout =
                earlyRequests(
                    port,
                    ticket,
                    listOf(Request("GET", "/logout"), Request("POST", "/pay", listOf(QpackHeaderField("idempotency-key", "k1")))),
                ) { streams ->
                    assertEquals(200, readStatus(streams[1]))
                    assertEquals(Handled("POST", "/pay", beforeConfirmation = true), withTimeout(STEP) { handled.receive() })
                    assertTrue(handled.isEmpty, "the GET marked unsafe ran before confirmation: ${handled.tryReceive()}")
                    channel.release()
                    readStatus(streams[0])
                }
            assertEquals(200, logout)
            assertEquals(Handled("GET", "/logout", beforeConfirmation = false), withTimeout(STEP) { handled.receive() })
        }

    // ---- harness --------------------------------------------------------------------------------

    private data class Request(
        val method: String,
        val path: String,
        val headers: List<QpackHeaderField> = emptyList(),
    )

    private fun earlyDataTest(
        replaySafety: Http3ReplaySafety,
        body: suspend (Int, QuicSessionTicket, HandshakeDropChannel, Channel<Handled>) -> Unit,
    ) = runBlocking {
        withTimeout(60.seconds) {
            val socket =
                try {
                    UdpSocket.bind("127.0.0.1", 0, receiveBufferSize = 1500, bufferFactory = BufferFactory.network())
                } catch (e: UnsatisfiedLinkError) {
                    assumeTrue("Native lib not available: ${e.message}", false)
                    error("unreachable")
                }
            val channel = HandshakeDropChannel(socket)
            val handled = Channel<Handled>(Channel.UNLIMITED)
            val serverOptions = clientOptions.copy(enableEarlyData = true)
            withQuicServer(QuicPortBinding.Shared(channel), tlsConfig(), serverOptions.forHttp3()) {
                val serving =
                    launch {
                        connections {
                            serveHttp3(replaySafety = replaySafety) {
                                handled.send(Handled(request.method, request.path, beforeConfirmation = channel.dropping))
                                response.send(200)
                            }
                        }
                    }
                try {
                    val ticket = firstConnection(port)
                    withTimeout(STEP) { handled.receive() }
                    channel.dropHandshakes()
                    body(port, ticket, channel, handled)
                } finally {
                    serving.cancel()
                }
            }
        }
    }

    private val clientOptions =
        QuicOptions(alpnProtocols = listOf(HTTP3_ALPN), verifyPeer = false, idleTimeout = 10.seconds, migration = MigrationPolicy.Manual)

    private suspend fun firstConnection(port: Int): QuicSessionTicket =
        withQuicConnection("127.0.0.1", port, clientOptions.forHttp3(), timeout = STEP) {
            val stream = openStream()
            stream.writeFullyAndShutdownSend(requestBytes(Request("GET", "/first")))
            assertEquals(200, readStatus(stream))
            val issued = withTimeout(STEP) { sessionTicket.first { it is QuicSessionTicketState.Issued } }
            assertIs<QuicSessionTicketState.Issued>(issued).ticket
        }

    /** Resume with [ticket], writing each of [requests] on its own stream as 0-RTT. */
    private suspend fun <R> earlyRequests(
        port: Int,
        ticket: QuicSessionTicket,
        requests: List<Request>,
        onStreams: suspend (List<QuicByteStream>) -> R,
    ): R {
        val early = CompletableDeferred<List<QuicByteStream>>()
        val options =
            clientOptions.forHttp3().copy(
                resumption =
                    QuicResumption.ResumeWithEarlyData(ticket) {
                        early.complete(
                            requests.map { request ->
                                openStream().also { it.writeFullyAndShutdownSend(requestBytes(request)) }
                            },
                        )
                    },
            )
        return withQuicConnection("127.0.0.1", port, options, timeout = STEP) {
            assertEquals(QuicResumptionOutcome.Resumed(QuicEarlyDataOutcome.Accepted), resumption, "0-RTT must be accepted")
            onStreams(withTimeout(STEP) { early.await() })
        }
    }

    /** A request HEADERS frame, static QPACK only. */
    private fun requestBytes(request: Request): ReadBuffer {
        val fields =
            listOf(
                QpackHeaderField(":method", request.method),
                QpackHeaderField(":scheme", "https"),
                QpackHeaderField(":authority", "localhost"),
                QpackHeaderField(":path", request.path),
            ) + request.headers
        val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
        check(size < 64) { "a one-byte frame length is all this writes" }
        val out = BufferFactory.deterministic().allocate(2 + size)
        out.writeByte(0x01) // HEADERS
        out.writeByte(size.toByte())
        QpackFieldSectionCodec.encode(out, fields, EncodeContext.Empty)
        out.resetForRead()
        return out
    }

    /** Read [stream] to its end and return the `:status` of the response HEADERS frame it starts with. */
    private suspend fun readStatus(stream: QuicByteStream): Int {
        val bytes = mutableListOf<Byte>()
        while (true) {
            when (val r = stream.read(STEP)) {
                is ReadResult.Data ->
                    try {
                        while (r.buffer.remaining() > 0) bytes += r.buffer.readByte()
                    } finally {
                        r.buffer.freeIfNeeded()
                    }
                ReadResult.End, ReadResult.Reset -> break
            }
        }
        var at = 0

        fun varInt(): Long {
            val first = bytes[at].toInt() and 0xff
            val width = 1 shl (first shr 6)
            var value = (first and 0x3f).toLong()
            for (i in 1 until width) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
            at += width
            return value
        }
        assertEquals(0x01L, varInt(), "the response starts with HEADERS")
        val length = varInt().toInt()
        val section = BufferFactory.deterministic().allocate(length)
        for (i in 0 until length) section.writeByte(bytes[at + i])
        section.resetForRead()
        val status = QpackFieldSectionCodec.decode(section, DecodeContext.Empty).first { it.name == ":status" }
        return status.value.toInt()
    }

    private fun tlsConfig(): QuicTlsConfig {
        fun path(name: String) =
            java.io
                .File(
                    (this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")).toURI(),
                ).absolutePath
        return QuicTlsConfig(certChainPath = path("cert.crt"), privKeyPath = path("cert.key"))
    }

    private companion object {
        val STEP = 20.seconds
    }
}

/**
 * While [dropping], drops every inbound datagram carrying a QUIC v1 Handshake packet, coalesced or not, so
 * the server never receives the client's Finished. [release] lets the client's retransmission through.
 */
private class HandshakeDropChannel(
    private val delegate: AddressedDatagramChannel,
) : AddressedDatagramChannel by delegate {
    @Volatile
    private var drop: Drop = Drop.Passing

    @Volatile
    var dropped: Int = 0
        private set

    val dropping: Boolean get() = drop is Drop.Dropping

    fun dropHandshakes() {
        drop = Drop.Dropping
    }

    fun release() {
        drop = Drop.Passing
    }

    override suspend fun receive(): DatagramReadResult {
        while (true) {
            val result = delegate.receive()
            if (result is DatagramReadResult.Received && drop is Drop.Dropping && carriesHandshake(result.datagram.payload)) {
                result.datagram.payload.freeNativeMemory()
                dropped++
                continue
            }
            return result
        }
    }

    private sealed interface Drop {
        data object Passing : Drop

        data object Dropping : Drop
    }

    private fun carriesHandshake(payload: PlatformBuffer): Boolean {
        var at = payload.position()
        val end = payload.limit()

        fun varIntAt(index: Int): Pair<Long, Int> {
            val first = payload[index].toInt() and 0xff
            val width = 1 shl (first shr 6)
            var value = (first and 0x3f).toLong()
            for (i in 1 until width) value = (value shl 8) or (payload[index + i].toLong() and 0xff)
            return value to width
        }
        while (at < end) {
            val first = payload[at].toInt() and 0xff
            if (first and 0x80 == 0) return false
            val type = (first shr 4) and 0x03
            if (type == 2) return true
            if (type == 3) return false
            var cursor = at + 5
            cursor += 1 + (payload[cursor].toInt() and 0xff)
            cursor += 1 + (payload[cursor].toInt() and 0xff)
            if (type == 0) {
                val (token, width) = varIntAt(cursor)
                cursor += width + token.toInt()
            }
            val (length, width) = varIntAt(cursor)
            at = cursor + width + length.toInt()
        }
        return false
    }
}
