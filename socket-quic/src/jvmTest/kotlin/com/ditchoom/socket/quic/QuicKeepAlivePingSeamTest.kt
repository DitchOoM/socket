package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-tightening integration test (JVM/JNI). Drives a **real** quiche client through a
 * [CountingQuicheApi] spy and asserts the reactive keepalive actually invoked the native
 * `quiche_conn_send_ack_eliciting` while the connection sat idle past its timeout — not merely that the
 * connection "survived". This closes the gap between the stub-based scheduling tests in
 * [ReactiveDriverTests] (which prove the driver logic, no native call) and [QuicIdleTimeoutTests]
 * (which prove end-to-end survival but don't directly observe the PING).
 */
class QuicKeepAlivePingSeamTest {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tls get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    @Test
    fun keepAlive_idleConnection_invokesRealAckEliciting() =
        runQuicTest(timeout = 20.seconds) {
            try {
                val opts =
                    QuicOptions(
                        alpnProtocols = listOf("test"),
                        verifyPeer = false,
                        idleTimeout = 4.seconds,
                        keepAliveInterval = 1.seconds,
                    )
                val counting = CountingQuicheApi(loadQuicheApi())
                withQuicServer(port = 0, tlsConfig = tls, quicOptions = opts) {
                    val serverJob = launch { echoEveryStream() }
                    try {
                        commonJvmWithQuicConnection(
                            hostname = "127.0.0.1",
                            port = port,
                            quicOptions = opts,
                            connectionOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic()),
                            timeout = 10.seconds,
                            api = counting,
                        ) {
                            val stream = openStream()
                            assertEquals("warmup", stream.echoOnce("warmup"), "warmup echo failed before idle wait")
                            delay(6.seconds) // idle past the 4 s timeout — only the real keepalive PING holds it open
                            assertEquals("alive", stream.echoOnce("alive"), "connection idle-closed despite keepalive")
                            assertTrue(
                                counting.ackElicitingCount >= 1,
                                "driver never invoked the real quiche_conn_send_ack_eliciting during the idle window " +
                                    "(count=${counting.ackElicitingCount})",
                            )
                            stream.close()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            } catch (e: UnsatisfiedLinkError) {
                assumeTrue("Native lib not available: ${e.message}", false)
            }
        }

    private suspend fun QuicServer.echoEveryStream() {
        connections {
            val stream = acceptStream()
            while (true) {
                val data = stream.read(9.seconds)
                if (data is ReadResult.Data) stream.write(data.buffer, 5.seconds) else break
            }
            stream.close()
        }
    }

    private suspend fun QuicByteStream.echoOnce(payload: String): String {
        val out = BufferFactory.deterministic().allocate(payload.length)
        out.writeString(payload, Charset.UTF8)
        out.resetForRead()
        try {
            write(out, 5.seconds)
        } finally {
            out.freeNativeMemory()
        }
        val resp = read(5.seconds)
        return if (resp is ReadResult.Data) {
            val s = resp.buffer.readString(resp.buffer.remaining(), Charset.UTF8)
            resp.buffer.freeIfNeeded()
            s
        } else {
            "no_data"
        }
    }
}
