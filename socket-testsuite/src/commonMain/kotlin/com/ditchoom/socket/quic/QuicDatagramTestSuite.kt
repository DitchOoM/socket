package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared end-to-end test suite for unreliable datagrams (RFC 9221). Each platform extends it with a
 * [testTlsConfig]; the bodies run real encrypted datagrams over loopback UDP, guaranteeing parity
 * across JVM, Linux, and Apple.
 *
 * Datagrams are unreliable, but a single datagram over loopback with no impairment does not drop, so
 * the round-trip assertions are deterministic. Control-flow/ownership edge cases live in the
 * platform-agnostic [QuicDatagramAdapterTests].
 */
abstract class QuicDatagramTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /** Datagrams disabled — the default. */
    private val noDgramOptions =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    /** Datagrams enabled on both ends. */
    private val dgramOptions = noDgramOptions.copy(datagrams = DatagramOptions())

    @Test
    fun datagramRoundTrip() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = dgramOptions) {
                    val echoed = CompletableDeferred<String>()

                    // Server: keep echoing every datagram on the connection (not just one). The client may
                    // resend (below), and each resend must be echoed so the round-trip completes.
                    val serverJob =
                        launch {
                            connections {
                                while (true) {
                                    when (val r = receiveDatagram()) {
                                        is DatagramReceiveResult.Received -> {
                                            val text = r.buffer.readString(r.buffer.remaining(), Charset.UTF8)
                                            r.buffer.freeIfNeeded()
                                            val out = BufferFactory.deterministic().allocate(text.length)
                                            out.writeString(text, Charset.UTF8)
                                            out.resetForRead()
                                            sendDatagram(out)
                                            out.freeNativeMemory()
                                        }
                                        is DatagramReceiveResult.ConnectionClosed -> break
                                    }
                                }
                            }
                        }
                    delay(100)

                    val clientJob =
                        launch {
                            withQuicConnection("localhost", port, dgramOptions, timeout = 10.seconds) {
                                assertIs<MaxDatagramSize.Bytes>(maxDatagramSize(), "datagrams should be sendable")
                                // RFC 9221 datagrams are UNRELIABLE and NW keeps no app-level pre-arm
                                // backlog, so the very first datagram can be lost to a setup-ordering race:
                                // the peer's datagram receive loop arms only a sub-millisecond margin before
                                // this send, and on a jittery/virtualized runner the send can beat the arm —
                                // the datagram is then dropped (proven via QUIC_NW_DIAG: server-ARM vs
                                // client-SEND within ~0.1-0.4ms; the macos-26 datagramRoundTrip flake). The
                                // robust pattern for an unreliable, no-backlog transport is resend-until-acked
                                // — the echo IS the ack. On a reliable backend the first send is echoed at
                                // once and the resender is cancelled after a single iteration, so this is a
                                // no-op there; it never weakens the assertion (one echo must still arrive).
                                coroutineScope {
                                    val resender =
                                        launch {
                                            while (isActive && !echoed.isCompleted) {
                                                val sendBuf = BufferFactory.deterministic().allocate(11)
                                                sendBuf.writeString("hello dgram", Charset.UTF8)
                                                sendBuf.resetForRead()
                                                sendDatagram(sendBuf)
                                                sendBuf.freeNativeMemory()
                                                delay(250)
                                            }
                                        }
                                    try {
                                        while (!echoed.isCompleted) {
                                            when (val r = receiveDatagram()) {
                                                is DatagramReceiveResult.Received -> {
                                                    echoed.complete(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                                    r.buffer.freeIfNeeded()
                                                }
                                                is DatagramReceiveResult.ConnectionClosed -> echoed.complete("connection_closed")
                                            }
                                        }
                                    } finally {
                                        resender.cancel()
                                    }
                                }
                            }
                        }

                    try {
                        assertEquals("hello dgram", withTimeout(10.seconds) { echoed.await() })
                    } finally {
                        clientJob.cancel()
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun datagramsDisabledByDefault() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = noDgramOptions) {
                    val ready = CompletableDeferred<Unit>()
                    val serverJob =
                        launch {
                            connections {
                                ready.complete(Unit)
                                // Return immediately; framework closes the connection.
                            }
                        }
                    delay(100)

                    try {
                        withQuicConnection("localhost", port, noDgramOptions, timeout = 10.seconds) {
                            assertEquals(MaxDatagramSize.Unavailable, maxDatagramSize())
                            val buf = BufferFactory.deterministic().allocate(4)
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

    @Test
    fun datagramTooLargeThrows() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = dgramOptions) {
                    val ready = CompletableDeferred<Unit>()
                    val serverJob = launch { connections { ready.complete(Unit) } }
                    delay(100)

                    try {
                        withQuicConnection("localhost", port, dgramOptions, timeout = 10.seconds) {
                            val max = maxDatagramSize()
                            assertIs<MaxDatagramSize.Bytes>(max)
                            assertTrue(max.bytes > 0)
                            val tooBig = BufferFactory.deterministic().allocate(max.bytes + 1)
                            repeat(max.bytes + 1) { tooBig.writeByte(0) }
                            tooBig.resetForRead()
                            assertFailsWith<IllegalArgumentException> { sendDatagram(tooBig) }
                            tooBig.freeNativeMemory()
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
