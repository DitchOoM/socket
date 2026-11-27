package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.IncomingStreams
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicStreamLimit
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The peer reads a stream, then resets it both ways (RESET_STREAM and STOP_SENDING) before our side finished it, on
 * real core ends over virtual time. Our close settles at once, the peer sees each stream once, and both ends let
 * the stream go in quiche too, so the peer's stream credit comes back and opens never wait for it.
 */
class StoppedStreamCreditVirtualTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            incomingStreams = IncomingStreams.Accept(bidirectional = QuicStreamLimit(LIMIT.toUInt())),
        )

    @Test
    fun streamsThePeerStopsAfterReadingCloseAtOnceAndGiveTheirCreditBack() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            var accepted = 0
            launch {
                // The accept loop ends with the connection, when the pair stops.
                runCatching {
                    while (true) {
                        val stream = serverSide.acceptStream()
                        accepted++
                        launch {
                            stream.read(1.seconds) { }
                            (stream as Resettable).reset(PEER_CODE)
                        }
                    }
                }
            }
            var done = 0
            var slowest = 0L
            launch {
                repeat(STREAMS) {
                    val stream = pair.client.connection.openStream()
                    stream.write(payload(), 1.seconds)
                    val got = stream.read(1.seconds)
                    if (got is ReadResult.Data) got.buffer.freeIfNeeded()
                    assertTrue(got === ReadResult.Reset, "the peer's reset reached the reader: $got")
                    val asked = pair.now()
                    runCatching { stream.close() }
                    slowest = maxOf(slowest, pair.now() - asked)
                    done++
                }
            }
            pair.runUntil(within = 60.seconds) { done == STREAMS }
            pair.runFor(200.milliseconds)
            assertTrue(slowest < 100.milliseconds.inWholeNanoseconds, "a close parked for ${slowest / 1_000_000} ms on a stopped stream")
            assertEquals(STREAMS, accepted, "the peer was handed each stream once")
        }

    @Test
    fun streamsThePeerAnsweredThenResetGiveTheirCreditBackWhicheverEndsFirst() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val gates = ArrayDeque<CompletableDeferred<Unit>>()
            launch {
                runCatching {
                    while (true) {
                        val stream = serverSide.acceptStream()
                        val gate = CompletableDeferred<Unit>().also { gates.addLast(it) }
                        launch {
                            stream.read(1.seconds) { }
                            stream.write(payload(), 1.seconds)
                            (stream as HalfCloseable).shutdownSend()
                            // Reset once our answer, FIN included, is acknowledged: our side is over before the peer's.
                            gate.await()
                            (stream as Resettable).reset(PEER_CODE)
                        }
                    }
                }
            }
            repeat(STREAMS) {
                var answered = false
                var closed = false
                launch {
                    val stream = pair.client.connection.openStream()
                    stream.write(payload(), 1.seconds)
                    while (stream.read(1.seconds).also { got ->
                            if (got is ReadResult.Data) got.buffer.freeIfNeeded()
                        } is ReadResult.Data
                    ) {
                        Unit
                    }
                    answered = true
                    while (!closed) yield()
                    runCatching { stream.close() }
                }
                pair.runUntil(within = 10.seconds) { answered && gates.isNotEmpty() }
                pair.runFor(pair.oneWay * 4)
                gates.removeFirst().complete(Unit)
                pair.runFor(pair.oneWay * 4)
                closed = true
                pair.runFor(pair.oneWay * 4)
            }
        }

    private fun payload(): PlatformBuffer =
        BufferFactory.Default.allocate(PAYLOAD).apply {
            repeat(PAYLOAD) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val LIMIT = 4
        const val STREAMS = 3 * LIMIT
        const val PAYLOAD = 32
        const val PEER_CODE = 7L
    }
}
