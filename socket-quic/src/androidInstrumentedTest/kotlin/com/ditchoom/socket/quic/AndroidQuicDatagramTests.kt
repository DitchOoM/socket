package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * In-process unreliable-datagram (RFC 9221) coverage on the Android/JNI runtime — the Android
 * counterpart of [QuicDatagramTestSuite] (JVM/Linux). Runs BOTH ends via [withQuicServer] over
 * loopback (no docker), exercising the Android cargo-ndk JNI datagram bindings end-to-end.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicDatagramTests {
    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    private val noDgramOptions =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)
    private val dgramOptions = noDgramOptions.copy(datagrams = DatagramOptions())

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun datagramRoundTrip() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = dgramOptions) {
                        val echoed = CompletableDeferred<String>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    when (val r = receiveDatagram()) {
                                        is DatagramReceiveResult.Received -> {
                                            val text = r.buffer.readString(r.buffer.remaining(), Charset.UTF8)
                                            r.buffer.freeIfNeeded()
                                            val out = BufferFactory.Default.allocate(text.length)
                                            out.writeString(text, Charset.UTF8)
                                            out.resetForRead()
                                            sendDatagram(out)
                                            out.freeNativeMemory()
                                        }
                                        is DatagramReceiveResult.ConnectionClosed -> {}
                                    }
                                }
                            }
                        delay(100)

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("127.0.0.1", port, dgramOptions, timeout = 10.seconds) {
                                    assertTrue(maxDatagramSize() is MaxDatagramSize.Bytes)
                                    val sendBuf = BufferFactory.Default.allocate(13)
                                    sendBuf.writeString("hello-android", Charset.UTF8)
                                    sendBuf.resetForRead()
                                    sendDatagram(sendBuf)
                                    sendBuf.freeNativeMemory()
                                    when (val r = receiveDatagram()) {
                                        is DatagramReceiveResult.Received -> {
                                            echoed.complete(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                            r.buffer.freeIfNeeded()
                                        }
                                        is DatagramReceiveResult.ConnectionClosed -> echoed.complete("connection_closed")
                                    }
                                }
                            }

                        try {
                            assertEquals("hello-android", withTimeout(12.seconds) { echoed.await() })
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun datagramsDisabledByDefault() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = noDgramOptions) {
                        val serverJob = launch(Dispatchers.IO) { connections { /* return immediately */ } }
                        delay(100)
                        try {
                            withQuicConnection("127.0.0.1", port, noDgramOptions, timeout = 10.seconds) {
                                assertEquals(MaxDatagramSize.Unavailable, maxDatagramSize())
                                val buf = BufferFactory.Default.allocate(4)
                                buf.writeInt(1)
                                buf.resetForRead()
                                assertFailsWith<IllegalStateException> { sendDatagram(buf) }
                                buf.freeNativeMemory()
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
