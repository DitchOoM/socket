package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class QuicLocalServerTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /**
     * Convert `UnsatisfiedLinkError` (raised by the lazy `loadQuicheApi()`
     * inside the helpers) into a JUnit assumption skip — keeps the test
     * silent on machines without a built JNI lib.
     */
    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun serverAcceptsConnection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val handlerRan = CompletableDeferred<Unit>()

                        // Handler-immediate pattern; delay(N) in handler deadlocks driver
                        // shutdown on CI (see QuicServerTestSuite.serverAcceptsConnection).
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    handlerRan.complete(Unit)
                                }
                            }

                        try {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                // Empty.
                            }
                            withTimeout(10.seconds) { handlerRan.await() }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun echoSingleStream() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val echoResult = CompletableDeferred<String>()

                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    val data = stream.read(5.seconds)
                                    if (data is com.ditchoom.buffer.flow.ReadResult.Data) {
                                        stream.write(data.buffer, 5.seconds)
                                    }
                                    stream.close()
                                }
                            }
                        delay(100)

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    val sendBuf = BufferFactory.Default.allocate(11)
                                    sendBuf.writeString("hello quic!", Charset.UTF8)
                                    sendBuf.resetForRead()
                                    stream.write(sendBuf, 5.seconds)

                                    val response = stream.read(5.seconds)
                                    if (response is com.ditchoom.buffer.flow.ReadResult.Data) {
                                        echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                    } else {
                                        echoResult.complete("no_data")
                                    }
                                    stream.close()
                                }
                            }

                        try {
                            val result = withTimeout(10.seconds) { echoResult.await() }
                            assertEquals("hello quic!", result)
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * #318, against real quiche: the peer answers a half-closed request, FINs the stream and closes
     * the connection, all before the application reads. Those bytes were accepted and acknowledged by
     * our transport, so the connection ending does not un-receive them (RFC 9000 §10.2) — the read
     * must still deliver `ping`.
     *
     * The emulator lane hit this by losing a scheduler race (a reader whose wakeup arrived after the
     * teardown, reporting `no_data:End`); here the losing interleaving is *forced*: the client simply
     * does not read until well past the connection's draining period, so the read provably runs after
     * its own driver tore the connection down and freed the quiche connection. Without the teardown
     * drain in QuicheDriver.transitionToClosed this returns `no_data:End` every time — the same
     * verdict, with the peer's reply discarded — which is the end-to-end mutation proof that the
     * drained bytes are real quiche's and not a stub's.
     */
    @Test
    fun replyBufferedWhenPeerClosesIsStillDelivered() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(30.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val echoResult = CompletableDeferred<String>()

                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    // Drain the request to the peer's FIN, echo it back, FIN our side.
                                    val received = StringBuilder()
                                    while (true) {
                                        val r = stream.read(5.seconds)
                                        if (r !is com.ditchoom.buffer.flow.ReadResult.Data) break
                                        received.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                    }
                                    val reply = BufferFactory.Default.allocate(received.length)
                                    reply.writeString(received.toString(), Charset.UTF8)
                                    reply.resetForRead()
                                    stream.write(reply, 5.seconds)
                                    stream.close()
                                    // Returning from the handler closes the connection: CONNECTION_CLOSE
                                    // reaches the client while its reply is still unread.
                                }
                            }
                        delay(100)

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    val sendBuf = BufferFactory.Default.allocate(4)
                                    sendBuf.writeString("ping", Charset.UTF8)
                                    sendBuf.resetForRead()
                                    stream.write(sendBuf, 5.seconds)
                                    stream.shutdownSend()

                                    // Outlast the draining period (3 × PTO, tens of ms) by a wide margin
                                    // so the reply, the FIN and the CONNECTION_CLOSE have all been
                                    // processed and the driver has torn the connection down before the
                                    // first read is even issued.
                                    delay(2.seconds)

                                    val response = stream.read(5.seconds)
                                    if (response is com.ditchoom.buffer.flow.ReadResult.Data) {
                                        echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                    } else {
                                        echoResult.complete("no_data:${response::class.simpleName}")
                                    }
                                    stream.close()
                                }
                            }

                        try {
                            assertEquals("ping", withTimeout(15.seconds) { echoResult.await() })
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
