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

    private fun engineOrSkip(): QuicEngine =
        try {
            defaultQuicEngine()
        } catch (e: Throwable) {
            assumeTrue("Native lib not available: ${e.message}", false)
            throw AssertionError("unreachable")
        }

    @Test
    fun serverAcceptsConnection() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val handlerRan = CompletableDeferred<Unit>()

                val serverJob =
                    launch(Dispatchers.IO) {
                        server.connections {
                            handlerRan.complete(Unit)
                            delay(3.seconds)
                        }
                    }
                delay(100)

                val clientEngine = engineOrSkip()
                val clientJob =
                    launch(Dispatchers.IO) {
                        clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
                            delay(3.seconds)
                        }
                    }

                withTimeout(10.seconds) { handlerRan.await() }

                clientJob.cancel()
                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }

    @Test
    fun echoSingleStream() =
        runBlocking(Dispatchers.IO) {
            withTimeout(15.seconds) {
                val serverEngine = defaultQuicServerEngine()
                val server = serverEngine.bind(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions)
                val echoResult = CompletableDeferred<String>()

                val serverJob =
                    launch(Dispatchers.IO) {
                        server.connections {
                            val stream = acceptStream()
                            val data = stream.read(5.seconds)
                            if (data is com.ditchoom.buffer.flow.ReadResult.Data) {
                                stream.write(data.buffer, 5.seconds)
                            }
                            stream.close()
                        }
                    }
                delay(100)

                val clientEngine = engineOrSkip()
                val clientJob =
                    launch(Dispatchers.IO) {
                        clientEngine.connect("localhost", server.port, testQuicOptions, timeout = 10.seconds) {
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

                val result = withTimeout(10.seconds) { echoResult.await() }
                assertEquals("hello quic!", result)

                clientJob.cancel()
                serverJob.cancel()
                server.close()
                clientEngine.close()
            }
        }
}
