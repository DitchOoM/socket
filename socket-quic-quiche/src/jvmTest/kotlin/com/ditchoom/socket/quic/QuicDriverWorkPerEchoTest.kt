package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * How much work the client's driver does for one echo on an established, idle connection, counted at the
 * quiche seam with the real native binding.
 *
 * Per echo the client has to send the request and take the reply in (and ACK it). It does not have to ask
 * the loop whether quiche holds bytes for a read that is about to wait for the network: the loop sweeps
 * the readable streams after every datagram and hands the bytes to the parked read. Before that was
 * relied on, each echo cost the client a `StreamRecv` that was always answered "nothing" (an extra loop
 * turn, flush and two thread hand-offs), and a second `quiche_conn_send` pass after the sweep.
 */
class QuicDriverWorkPerEchoTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private class WorkCountingApi(
        private val delegate: QuicheApi,
    ) : QuicheApi by delegate {
        val sends = AtomicInteger()
        val streamRecvs = AtomicInteger()

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            sends.incrementAndGet()
            return delegate.connSend(conn, buf, bufLen, sendInfo)
        }

        override fun connStreamRecv(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
        ): StreamRecvResult {
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
                            // One stream read per echo: the bytes the loop hands the parked read. Any more is
                            // a read that asked the loop first (or an echo split in two).
                            assertTrue(recvs <= ECHOES, "client stream reads for $ECHOES echoes: $recvs (want exactly $ECHOES)")
                            // Request flush (data + done), the reply's turn (ACK + done) and the turn of the
                            // server's ACK (done): 5 at best. The old loop made 7.
                            assertTrue(
                                sends <= ECHOES * 5 + TIMER_FLUSHES,
                                "quiche_conn_send calls for $ECHOES echoes: $sends (want at most ${ECHOES * 5 + TIMER_FLUSHES})",
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

        /**
         * `quiche_conn_send` passes a timer wake adds beyond the five an echo costs (the ACK and loss timers
         * firing mid-run, each one a flush that finds nothing or one ACK): measured 2,545 for 2,500 on JNI.
         */
        const val TIMER_FLUSHES = 50
    }
}
