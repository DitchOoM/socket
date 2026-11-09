package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The harness echo server ([echoConnection]) echoes a stream whose first frame arrives late.
 *
 * Under loss it does: in `QuicImpairedHarnessTests.aStreamSurvivesLossOnBothLegs` the client's first
 * stream packet was dropped, and its retransmission waited out a PTO inflated by the lossy handshake's
 * RTT sample (srtt 1 s, rttvar 0.5 s), so it reached the server 4.5 s after the handshake. The server
 * accepted streams for 3 s only, so it never echoed, and the client idled out 30 s later. That was 4 runs
 * in 60 of an in-process copy of that test: the runs where the drop-every-4th schedule landed on that packet.
 *
 * Opening the stream [LATE] after the handshake reproduces that arrival time without loss, on every run.
 */
class EchoConnectionLateStreamTests {
    private val bufferFactory = BufferFactory.network()

    private val quicOptions =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds)

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    @Test
    fun aStreamOpenedLongAfterTheHandshakeIsStillEchoed() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(EchoConnectionLateStreamTests::class) {
                val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = quicOptions) {
                    val serverJob = launch(Dispatchers.IO) { connections { echoConnection(quicOptions) } }
                    try {
                        withQuicConnection("127.0.0.1", port, quicOptions, timeout = 30.seconds) {
                            delay(LATE)
                            val stream = openStream()
                            val out = bufferFactory.allocate(PAYLOAD.length)
                            out.writeString(PAYLOAD, Charset.UTF8)
                            out.resetForRead()
                            stream.write(out, 10.seconds)
                            out.freeNativeMemory()
                            val read = stream.read(10.seconds)
                            assertIs<ReadResult.Data>(
                                read,
                                "the echo server did not answer a stream opened $LATE after the handshake: $read",
                            )
                            assertEquals(PAYLOAD, read.buffer.readString(read.buffer.remaining(), Charset.UTF8))
                            read.buffer.freeIfNeeded()
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    private companion object {
        const val PAYLOAD = "late-stream"

        /** Past the former 3 s accept window, as the lossy run's 4.5 s was. */
        val LATE = 5.seconds
    }
}
