package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How much work the client's driver does for one echo on an established, idle connection, counted at the
 * quiche seam with the real native binding: about one stream read and five `quiche_conn_send` calls per echo.
 * A read that waits for the network does not ask the loop first, because the loop announces or hands over
 * what a received datagram made readable.
 */
class QuicDriverWorkPerEchoTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /**
     * Counts what the loop asks of quiche, split by what woke the turn. A turn a timer woke calls
     * `quiche_conn_on_timeout` (or sends a keepalive PING) before its flush; a turn a command or a datagram
     * woke runs a `recv` or a stream call first. How many timer turns fit in a run depends on how long the
     * run takes, so only the command-and-datagram turns are counted against the bound: a slow machine gets
     * more timer turns and the same count here.
     */
    private class WorkCountingApi(
        private val delegate: QuicheApi,
    ) : QuicheApi by delegate {
        /** `quiche_conn_send` calls in turns a stream command or a received datagram woke. */
        val sends = AtomicInteger()

        /** `quiche_conn_send` calls in turns a timer woke (not bounded: their number is the run's duration). */
        val timerSends = AtomicInteger()
        val streamRecvs = AtomicInteger()

        /** Datagrams quiche wrote, in any turn: a `quiche_conn_send` that produced one. */
        val datagrams = AtomicInteger()

        /** Datagrams fed to quiche. */
        val datagramsIn = AtomicInteger()

        // Loop-confined: every call below is made by the one driver loop this api backs.
        private var timerTurn = false

        override fun connOnTimeout(conn: QuicheConn) {
            timerTurn = true
            delegate.connOnTimeout(conn)
        }

        override fun connSendAckEliciting(conn: QuicheConn): Int {
            timerTurn = true
            return delegate.connSendAckEliciting(conn)
        }

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            timerTurn = false
            datagramsIn.incrementAndGet()
            return delegate.connRecv(conn, buf, bufLen, recvInfo)
        }

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            (if (timerTurn) timerSends else sends).incrementAndGet()
            val written = delegate.connSend(conn, buf, bufLen, sendInfo)
            if (written > 0) datagrams.incrementAndGet()
            return written
        }

        override fun connStreamSend(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
            fin: Boolean,
        ): StreamSendResult {
            timerTurn = false
            return delegate.connStreamSend(conn, streamId, buf, bufLen, fin)
        }

        override fun connStreamRecv(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
        ): StreamRecvResult {
            timerTurn = false
            streamRecvs.incrementAndGet()
            return delegate.connStreamRecv(conn, streamId, buf, bufLen)
        }
    }

    @Test
    fun anEchoCostsTheClientOneStreamReadAndFewQuicheSendPasses() =
        runQuicTest(timeout = 30.seconds) {
            try {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
                val counting = WorkCountingApi(loadQuicheApi())
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    if (stream.read(9.seconds) { stream.write(it, 5.seconds) } !is ScopedRead.Data) break
                                }
                                stream.close()
                            }
                        }
                    try {
                        commonJvmWithQuicConnection(
                            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                            serverName = "localhost",
                            quicOptions = opts,
                            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            timeout = 10.seconds,
                            api = counting,
                        ) {
                            val stream = openStream()
                            repeat(WARMUP) { echo(stream) }
                            val sendsBefore = counting.sends.get()
                            val recvsBefore = counting.streamRecvs.get()
                            repeat(ECHOES) { echo(stream) }
                            val sends = counting.sends.get() - sendsBefore
                            val recvs = counting.streamRecvs.get() - recvsBefore
                            // One stream read per echo: the bytes the loop hands the parked read. A read asks the
                            // loop only when the reply beats it to the park, which a loaded machine makes more likely.
                            assertTrue(
                                recvs <= ECHOES * 3 / 2,
                                "client stream reads for $ECHOES echoes: $recvs (want about $ECHOES)",
                            )
                            // Request flush (data + done), the reply's turn (ACK + done) and the turn of the
                            // server's ACK (done): five, a little more when a read asks or a datagram splits a turn.
                            assertTrue(
                                sends <= ECHOES * 6,
                                "quiche_conn_send calls for $ECHOES echoes: $sends (want about ${ECHOES * 5}; timer turns not counted)",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(QuicDriverWorkPerEchoTest::class, e)
            }
        }

    /**
     * The client's ACK of a reply leaves in the datagram that carries its next request, so a run of echoes
     * costs the client about one datagram each, not a request and an ACK-only datagram.
     */
    @Test
    fun anEchoCostsTheClientAboutOneDatagram() =
        runQuicTest(timeout = 30.seconds) {
            try {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
                val counting = WorkCountingApi(loadQuicheApi())
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    if (stream.read(9.seconds) { stream.write(it, 5.seconds) } !is ScopedRead.Data) break
                                }
                                stream.close()
                            }
                        }
                    try {
                        commonJvmWithQuicConnection(
                            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                            serverName = "localhost",
                            quicOptions = opts,
                            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            timeout = 10.seconds,
                            api = counting,
                        ) {
                            val stream = openStream()
                            repeat(WARMUP) { echo(stream) }
                            val before = counting.datagrams.get()
                            repeat(ECHOES) { echo(stream) }
                            val datagrams = counting.datagrams.get() - before
                            assertTrue(
                                datagrams <= ECHOES * 5 / 4,
                                "client datagrams for $ECHOES echoes: $datagrams (want about $ECHOES)",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(QuicDriverWorkPerEchoTest::class, e)
            }
        }

    /**
     * A request the server reads and does not answer is acknowledged by the held flush's own deadline:
     * the client sees the ACK datagram and never has to probe for it.
     */
    @Test
    fun aRequestThatGetsNoReplyIsAcknowledgedWithoutARetransmission() =
        runQuicTest(timeout = 30.seconds) {
            try {
                val opts = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false)
                val counting = WorkCountingApi(loadQuicheApi())
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                while (true) {
                                    val read =
                                        stream.read(9.seconds) {
                                            if (it.get(it.position()) == ANSWERED) stream.write(it, 5.seconds)
                                        }
                                    if (read !is ScopedRead.Data) break
                                }
                                stream.close()
                            }
                        }
                    try {
                        commonJvmWithQuicConnection(
                            endpoint = QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                            serverName = "localhost",
                            quicOptions = opts,
                            connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            timeout = 10.seconds,
                            api = counting,
                        ) {
                            val stream = openStream()
                            repeat(WARMUP) { echo(stream) }
                            // The last reply's ACK leaves on the client's own held-flush deadline; let it go.
                            delay(50.milliseconds)
                            val before = counting.datagrams.get()
                            repeat(UNANSWERED) {
                                val seen = counting.datagramsIn.get()
                                send(stream, UNANSWERED_MARK)
                                withTimeout(5.seconds) { while (counting.datagramsIn.get() == seen) delay(1.milliseconds) }
                            }
                            // One datagram per request and nothing else: an ACK that came late would have
                            // cost the client a probe.
                            assertEquals(
                                UNANSWERED,
                                counting.datagrams.get() - before,
                                "client datagrams for $UNANSWERED unanswered requests",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                recordMissingNativeLib(QuicDriverWorkPerEchoTest::class, e)
            }
        }

    private suspend fun send(
        stream: QuicByteStream,
        mark: Byte,
    ) {
        val out = BufferFactory.deterministic().allocate(64)
        repeat(64) { out.writeByte(mark) }
        out.resetForRead()
        try {
            stream.write(out, 5.seconds)
        } finally {
            out.freeNativeMemory()
        }
    }

    private suspend fun echo(stream: QuicByteStream) {
        val out = BufferFactory.deterministic().allocate(64)
        repeat(64) { out.writeByte(1) }
        out.resetForRead()
        try {
            stream.write(out, 5.seconds)
        } finally {
            out.freeNativeMemory()
        }
        var got = 0
        while (got < 64) {
            val r = stream.read(5.seconds)
            assertEquals(true, r is ReadResult.Data, "echo ended early after $got bytes")
            r as ReadResult.Data
            got += r.buffer.remaining()
            r.buffer.freeIfNeeded()
        }
    }

    private companion object {
        const val WARMUP = 200
        const val ECHOES = 500

        /** More than the gate tolerates in a row, so both an open and a shut gate are crossed. */
        const val UNANSWERED = 8
        const val ANSWERED: Byte = 1
        const val UNANSWERED_MARK: Byte = 0
    }
}
