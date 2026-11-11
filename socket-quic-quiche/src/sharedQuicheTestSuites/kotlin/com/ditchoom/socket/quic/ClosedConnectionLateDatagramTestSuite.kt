@file:OptIn(com.ditchoom.buffer.flow.ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.SelectBuilder
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

/**
 * A datagram for a connection the server has just closed and removed is a normal part of every close —
 * the peer's own CONNECTION_CLOSE crossing ours, an ACK already in flight — and RFC 9000 §10.2 has the
 * closing endpoint keep enough state to recognise it. So the server keeps a closed connection's ids for
 * its draining period and reports such a datagram as an expected drop, not as an unknown id.
 *
 * Deterministic: the late datagram is sent by the test itself, only once the server no longer routes the
 * connection, carrying the id the client was using as its destination. And the draining period is
 * measured on a clock the test holds still ([HeldClock]): on loopback that period is three PTOs, about
 * 80 ms, and on a loaded iOS simulator runner (load average near 300) the test's own steps between the
 * close and the datagram's arrival once took longer than that, so the ids had rightly expired and the
 * datagram was an unknown id. The test then moves the clock past the period and checks that the same
 * datagram is no longer recognised, so the hold is what keeps the ids, and the period does end.
 */
abstract class ClosedConnectionLateDatagramTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** This platform's server, bound as [binding] asks, with [tuning]'s clock. */
    internal abstract suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
        tuning: QuicheDriverTuning,
    ): SharedQuicheServer

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    @Test
    fun aLateDatagramForAConnectionTheServerJustClosedIsAnExpectedDropNotAnUnknownId() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val events = Channel<TraceEvent>(Channel.UNLIMITED)
                val options = QuicOptions(alpnProtocols = listOf("late-datagram"), verifyPeer = false)
                val serverOptions = options.copy(trace = QuicTraceCapture(TraceSink { events.trySend(it) }))
                val clock = HeldClock()
                val server =
                    buildServer(
                        QuicPortBinding.Own(port = 0, host = "127.0.0.1"),
                        testTlsConfig(),
                        serverOptions,
                        // What QuicheEngine.bind builds, with the held clock.
                        QuicheDriverTuning(clock = clock, captureFactory = { traceRecorderFor(serverOptions) }),
                    )
                val port = server.port
                // The id the client addresses this connection by: the server's own source id.
                val clientsDestination = CompletableDeferred<QuicWireConnectionId>()
                // The handler returns at once, having written nothing, so the server closes at once.
                val serverJob = launch { server.connections { clientsDestination.complete(identity.wire) } }
                val clientJob =
                    launch {
                        runCatching { withQuicConnection("127.0.0.1", port, options) { awaitCancellation() } }
                    }
                try {
                    val wire = withTimeout(CLOSE_BOUND) { clientsDestination.await() }
                    val hex = (wire as? QuicWireConnectionId.Known)?.hex ?: fail("the server reported no source id: $wire")
                    val key = hex.toConnectionIdKey()
                    withTimeoutOrNull(CLOSE_BOUND) { while (server.routesConnectionIdForTest(key)) delay(1.milliseconds) }
                        ?: fail("the server still routes the connection $CLOSE_BOUND after its handler returned")

                    sendShortHeaderDatagram(port, hex, LATE_DATAGRAM_BYTES)
                    val late = events.recordOf(LATE_DATAGRAM_BYTES)
                    assertTrue(
                        late is TraceEvent.Drop,
                        "a datagram for a connection closed a moment ago must be an expected drop, not an error: $late",
                    )
                    assertEquals(ServerDatagramDrop.ForClosedConnection::class.qualifiedName, late.type, "$late")

                    // Past any draining period (three PTOs; at most a few seconds before an RTT sample).
                    clock.advance(1.hours)
                    sendShortHeaderDatagram(port, hex, EXPIRED_DATAGRAM_BYTES)
                    val expired = events.recordOf(EXPIRED_DATAGRAM_BYTES)
                    assertEquals(
                        ServerDatagramDrop.NotAnInitial::class.qualifiedName,
                        expired.type,
                        "once the draining period is over the closed connection's ids are forgotten, so the same " +
                            "datagram is an unknown id; anything else means the hold, not the period, decided: $expired",
                    )
                } finally {
                    clientJob.cancelAndJoin()
                    serverJob.cancelAndJoin()
                    server.close()
                }
            }
        }

    /** The server's record of the [bytes]-byte datagram this test sent. */
    private suspend fun Channel<TraceEvent>.recordOf(bytes: Int): TraceEvent =
        withTimeoutOrNull(CLOSE_BOUND) {
            var found: TraceEvent? = null
            while (found == null) {
                val e = receive()
                if (e.describesDatagramOf(bytes)) found = e
            }
            found
        } ?: fail("the server recorded nothing for the late $bytes-byte datagram")

    /**
     * Real timers, so the connection's handshake and close run as they do in production, and a monotonic
     * time that moves only when the test [advance]s it, so the server's draining period cannot run out
     * while the test is merely slow.
     */
    private class HeldClock : DriverClock {
        private val time = TestTimeSource()

        fun advance(by: Duration) {
            time += by
        }

        override fun markNow(): TimeMark = time.markNow()

        override fun armTimeout(
            builder: SelectBuilder<QuicheCmd?>,
            wait: Duration,
        ) = RealDriverClock.armTimeout(builder, wait)
    }

    /** A 1-RTT (short-header) packet addressed to [destinationHex], as a peer's late packet would be. */
    private suspend fun sendShortHeaderDatagram(
        port: Int,
        destinationHex: String,
        bytes: Int,
    ) {
        val socket = UdpSocket.connect("127.0.0.1", port)
        try {
            val datagram = BufferFactory.deterministic().allocate(bytes)
            datagram.writeByte(SHORT_HEADER_FIRST_BYTE)
            destinationHex.chunked(2).forEach { datagram.writeByte(it.toInt(16).toByte()) }
            while (datagram.position() < bytes) datagram.writeByte(0x5A)
            datagram.resetForRead()
            socket.send(datagram)
            datagram.freeNativeMemory()
        } finally {
            socket.close()
        }
    }

    private fun String.toConnectionIdKey(): ConnectionIdKey {
        val bytes = BufferFactory.deterministic().allocate(length / 2)
        chunked(2).forEach { bytes.writeByte(it.toInt(16).toByte()) }
        bytes.resetForRead()
        return ConnectionIdKey.from(bytes, offset = 0, length = length / 2).also { bytes.freeNativeMemory() }
    }

    /** Whether [this] is the server's record of a datagram of [bytes] bytes it could not hand to a connection. */
    private fun TraceEvent.describesDatagramOf(bytes: Int): Boolean =
        when (this) {
            is TraceEvent.Drop -> message.startsWith("$bytes B ")
            is TraceEvent.Error -> type.startsWith(SERVER_DROP_PREFIX) && message.startsWith("$bytes B ")
            else -> false
        }

    private val TraceEvent.type: String
        get() =
            when (this) {
                is TraceEvent.Drop -> type
                is TraceEvent.Error -> type
                else -> "-"
            }

    private companion object {
        /** Header form 0 (short), fixed bit 1: a 1-RTT packet. */
        const val SHORT_HEADER_FIRST_BYTE: Byte = 0x41

        /** A size nothing else on this connection sends, so the record names this datagram alone. */
        const val LATE_DATAGRAM_BYTES = 57

        /** The same datagram sent again after the draining period, told apart by its size. */
        const val EXPIRED_DATAGRAM_BYTES = 58

        val CLOSE_BOUND = 5.seconds

        val SERVER_DROP_PREFIX = ServerDatagramDrop::class.qualifiedName + "."
    }
}
