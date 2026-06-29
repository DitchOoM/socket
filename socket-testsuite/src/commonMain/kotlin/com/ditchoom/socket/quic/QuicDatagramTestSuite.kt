package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
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
        // Budget covers several withLiveQuicConnection attempts: on the virtualized macos-26 CI loopback a
        // connection can come up datagram-WEDGED (handshakes through a transient `Network is down` flap,
        // reaches ready, but its datagram channel silently passes no bytes for the connection's whole life).
        // The resend loop below cannot recover a wedged connection — only a FRESH, post-storm one can — so
        // the round-trip itself is the liveness probe and a wedge retries a new connection.
        runQuicTest(timeout = 50.seconds) {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = dgramOptions) {
                    // Server: keep echoing every datagram on EACH accepted connection (a retry below opens a
                    // fresh one). The client may resend, and each resend must be echoed so the round-trip
                    // completes.
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

                    var roundTripped: String? = null
                    try {
                        withLiveQuicConnection(
                            "localhost",
                            port,
                            dgramOptions,
                            timeout = 10.seconds,
                            reason = "datagram round-trip never completed (connection came up datagram-wedged)",
                        ) { confirmLive ->
                            assertIs<MaxDatagramSize.Bytes>(maxDatagramSize(), "datagrams should be sendable")
                            // RFC 9221 datagrams are UNRELIABLE and NW keeps no app-level pre-arm backlog, so
                            // the very first datagram can be lost to a setup-ordering race (peer's recv loop
                            // arms a sub-ms margin before this send; on a jittery runner the send beats the arm
                            // → dropped). The robust pattern is resend-until-acked — the echo IS the ack. We
                            // bound the wait: a HEALTHY connection echoes within a resend or two; a WEDGED one
                            // never echoes, so on timeout we abandon it and retry a fresh connection (the resend
                            // loop alone can't escape a wedge). On a reliable backend the first send is echoed
                            // at once, so this is a single-iteration no-op that never weakens the assertion.
                            val echoed = CompletableDeferred<String>()
                            val gotEcho =
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
                                        withTimeout(4.seconds.scaled) {
                                            while (!echoed.isCompleted) {
                                                when (val r = receiveDatagram()) {
                                                    is DatagramReceiveResult.Received -> {
                                                        echoed.complete(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                                                        r.buffer.freeIfNeeded()
                                                    }
                                                    is DatagramReceiveResult.ConnectionClosed ->
                                                        return@withTimeout
                                                }
                                            }
                                        }
                                        echoed.isCompleted
                                    } catch (e: TimeoutCancellationException) {
                                        false // no echo within the budget — datagram-wedged connection
                                    } finally {
                                        resender.cancel()
                                    }
                                }
                            if (!gotEcho) retryConnection()
                            confirmLive()
                            roundTripped = echoed.getCompleted()
                        }
                        assertEquals("hello dgram", roundTripped)
                    } finally {
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
