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
}
