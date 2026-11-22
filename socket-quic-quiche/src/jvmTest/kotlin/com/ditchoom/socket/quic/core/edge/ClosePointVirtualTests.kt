package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.demux.ServerPhase
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A close is a point in a connection's order of work, on a real core client and server over a 60 ms round trip, on
 * virtual time: what was written before it is delivered and acknowledged, every write after it is refused at once, a
 * graceful close returns as soon as that is done and at its limit at the latest, saying which, and the peer's callers
 * learn of the close when its CONNECTION_CLOSE arrives.
 */
class ClosePointVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun everyByteWrittenBeforeTheClosePointIsDeliveredAndEveryWriteAfterItIsRefusedAtOnce() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val received = LongArray(1)
            val readers = IntArray(1)
            val streams = List(WRITERS) { pair.client.connection.openStream() }
            for (stream in streams) written(pair, stream, 1)
            launch {
                repeat(WRITERS) {
                    val stream = serverSide.acceptStream()
                    launch {
                        readAll(stream, received)
                        readers[0]++
                    }
                }
            }
            val writers = List(WRITERS) { Writer(streams[it]) }
            for (writer in writers) launch { writer.writeUntilRefused(pair) }
            pair.runFor(WRITING)
            assertTrue(writers.all { it.refusedAt < 0 }, "every writer is still writing when the close is asked")

            val asked = pair.now()
            var returned = -1L
            var outcome: CloseOutcome = CloseOutcome.Immediate
            launch {
                outcome = pair.client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = pair.now()
            }
            pair.runUntil(within = LIMIT * 2) { returned >= 0 }
            pair.runUntil { readers[0] == WRITERS }

            assertSame(CloseOutcome.Drained, outcome, "everything accepted before the point was acknowledged")
            assertTrue(returned - asked < LIMIT.inWholeNanoseconds, "close returned ${(returned - asked) / MS} ms after it was asked")
            val accepted = writers.sumOf { it.accepted } + WRITERS
            assertTrue(writers.sumOf { it.accepted } > 0)
            assertEquals(accepted, received[0], "the peer received exactly the bytes accepted before the close point")
            for (writer in writers) {
                assertTrue(writer.refusedAt in asked until asked + MS, "refused ${(writer.refusedAt - asked) / MS} ms after the close")
                assertEquals(listOf<QuicCloseCircumstance>(QuicCloseCircumstance.Closing, QuicCloseCircumstance.Closing), writer.refusals)
                assertEquals(writer.refusedAt, writer.refusedAgainAt, "a write after the point is refused without waiting")
            }
        }

    @Test
    fun aGracefulCloseReturnsAsSoonAsWhatWasWrittenIsAcknowledged() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val got = LongArray(1)
            launch { readAll(serverSide.acceptStream(), got) }
            written(pair, pair.client.connection.openStream(), BULK)
            val asked = pair.now()
            var returned = -1L
            var outcome: CloseOutcome = CloseOutcome.Immediate
            launch {
                outcome = pair.client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = pair.now()
            }
            pair.runUntil { returned >= 0 }
            assertSame(CloseOutcome.Drained, outcome)
            assertTrue(returned - asked < 1.seconds.inWholeNanoseconds, "the close took ${(returned - asked) / MS} ms of a $LIMIT")
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            assertEquals(BULK.toLong(), got[0])
        }

    @Test
    fun aPeerThatStopsAcknowledgingEndsAGracefulCloseAtItsLimitAndTheOutcomeSaysSo() =
        VirtualPair.with(options) { pair ->
            pair.accept()
            written(pair, pair.client.connection.openStream(), UNREAD)
            pair.lose(End.Server, Int.MAX_VALUE)
            val asked = pair.now()
            var returned = -1L
            var outcome: CloseOutcome = CloseOutcome.Immediate
            launch {
                outcome = pair.client.connection.close(CloseChoice.Graceful(SHORT_LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = pair.now()
            }
            pair.runUntil(within = SHORT_LIMIT * 3) { returned >= 0 }
            assertSame(CloseOutcome.LimitReached, outcome, "nothing was acknowledged: the limit ended the close")
            val took = returned - asked
            assertTrue(took >= SHORT_LIMIT.inWholeNanoseconds, "the close waited its limit: ${took / MS} ms")
            assertTrue(took < SHORT_LIMIT.inWholeNanoseconds + 10 * MS, "and no longer: ${took / MS} ms")
            assertTrue(
                pair.sentBy(End.Client, asked + SHORT_LIMIT.inWholeNanoseconds).isNotEmpty(),
                "the CONNECTION_CLOSE went at the limit",
            )
        }

    @Test
    fun anImmediateCloseCutsADrainingGracefulCloseShort() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            written(pair, pair.client.connection.openStream(), UNREAD)
            pair.lose(End.Server, Int.MAX_VALUE)
            val asked = pair.now()
            var returned = -1L
            var outcome: CloseOutcome = CloseOutcome.Drained
            launch {
                outcome = pair.client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = pair.now()
            }
            pair.runFor(CUT_AFTER)
            assertEquals(-1L, returned, "the graceful close is still draining")
            var cut: CloseOutcome = CloseOutcome.Drained
            launch { cut = pair.client.connection.close(CloseChoice.Immediate(QuicError.ApplicationError(APP_CODE))) }
            pair.runUntil { returned >= 0 }
            assertSame(CloseOutcome.Immediate, outcome, "the drain was cut short")
            assertSame(CloseOutcome.Immediate, cut)
            assertTrue(
                returned - asked < CUT_AFTER.inWholeNanoseconds + 10 * MS,
                "returned ${(returned - asked) / MS} ms after the first close",
            )
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByPeer>(end).error, "the immediate close's error went")
        }

    @Test
    fun thePeersCallersLearnOfTheCloseWhenItArrivesStillReadWhatItHeldAndItsIdsLingerThroughTheDrain() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            // The server's data reaches the client after the client closed: a late packet the client answers.
            launch { serverSide.openStream().writeFully(bytes(PAYLOAD), 1.hours) }
            written(pair, pair.client.connection.openStream(), PAYLOAD)
            pair.runFor(1.milliseconds)
            val asked = pair.now()
            var returned = false
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned = true
            }
            pair.runUntil { returned }
            val closeSent = pair.sentBy(End.Client, asked).first().at
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            val told = pair.now() - closeSent
            assertTrue(
                told <= pair.oneWay.inWholeNanoseconds + MS,
                "the server's callers learned of the close ${told / MS} ms after it was sent; the path is ${pair.oneWay} one way",
            )
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByPeer>(end).error)
            assertEquals(1, pair.server.parts.connections, "the server's connection still holds its ids, draining")

            val held = LongArray(1)
            readAll(serverSide.acceptStream(), held)
            assertEquals(PAYLOAD.toLong(), held[0], "what the server had received and nobody read is read after the end")

            pair.runFor(pair.oneWay * 4)
            assertTrue(pair.sentBy(End.Client, asked).size >= 2, "the client answered the server's late data with its close")
            assertEquals(1, pair.server.parts.connections, "the drain lasts three PTOs")
            pair.runUntil(within = 5.seconds) { pair.server.parts.connections == 0 }
            assertTrue(pair.now() - closeSent >= 3 * 2 * pair.oneWay.inWholeNanoseconds, "the ids lingered through the drain")
        }

    @Test
    fun aServerShuttingDownClosesEachConnectionGracefullyAndDeliversWhatItWrote() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val got = LongArray(1)
            launch { readAll(pair.client.connection.acceptStream(), got) }
            written(pair, serverSide.openStream(), BULK)
            assertTrue(pair.loseInFlight(End.Server) > 0, "the last of the server's data is lost on the way")
            // The pair's thread passes the server's loop: the shutdown begins on it, as the server's close asks.
            pair.server.parts.beginShutdown()
            pair.runUntil(within = 20.seconds) { pair.server.parts.phase() == ServerPhase.Stopped }
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertEquals(BULK.toLong(), got[0], "the lost bytes were resent before the server's CONNECTION_CLOSE")
            assertSame(ConnEnd.ServerClosed, assertIs<EdgeEnd.Ended>(serverSide.end()).end)
            assertSame(CloseOutcome.Drained, assertIs<EdgeEnd.Ended>(serverSide.end()).outcome)
        }

    @Test
    fun aServerConnectionClosedGracefullyDeliversWhatItWrote() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val got = LongArray(1)
            launch { readAll(pair.client.connection.acceptStream(), got) }
            written(pair, serverSide.openStream(), BULK)
            assertTrue(pair.loseInFlight(End.Server) > 0, "the last of the server's data is lost on the way")
            var outcome: CloseOutcome = CloseOutcome.Immediate
            var returned = false
            launch {
                outcome = serverSide.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = true
            }
            pair.runUntil { returned }
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertSame(CloseOutcome.Drained, outcome)
            assertEquals(BULK.toLong(), got[0])
        }

    @Test
    fun aServerConnectionWhoseHandshakeCompletesInTheBatchThatClosesItIsOfferedThenEndsByPeer() =
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            pair.runUntil { pair.sentBy(End.Client).isNotEmpty() }
            // The client's Finished and its CONNECTION_CLOSE reach the server together.
            pair.hold(End.Client)
            pair.runUntil { pair.client.connection.state.value is QuicConnectionState.Established }
            var closed = false
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                closed = true
            }
            pair.runUntil { closed }
            assertTrue(pair.heldFrom(End.Client) >= 2, "the client's Finished and its close are held")
            pair.release(End.Client)
            var offered: CoreConnection? = null
            launch { offered = pair.accept() }
            pair.runUntil { offered != null }
            val serverSide = checkNotNull(offered)
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByPeer>(end).error)
            assertEquals("test", serverSide.negotiatedAlpn, "the completed handshake was reported")
        }

    @Test
    fun aClientWhoseHandshakeCompletesInTheBatchThatClosesItIsReportedEstablishedThenEndsByPeer() =
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            // The server's flight that completes the client's handshake and the server's close reach the client together.
            pair.hold(End.Server)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            val flight = pair.heldFrom(End.Server)
            pair.server.parts.beginShutdown()
            pair.runUntil { pair.heldFrom(End.Server) > flight }
            pair.release(End.Server)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertIs<ConnEnd.ByPeer>(assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
            assertEquals("test", pair.client.connection.negotiatedAlpn, "the completed handshake was reported")
        }

    /** Writes one stream in a loop until a write is refused, then writes once more. */
    private class Writer(
        private val stream: QuicByteStream,
    ) {
        private val chunk: PlatformBuffer = BufferFactory.Default.allocate(CHUNK)
        var accepted = 0L
        var refusedAt = -1L
        var refusedAgainAt = -1L
        val refusals = ArrayList<QuicCloseCircumstance>()

        suspend fun writeUntilRefused(pair: VirtualPair) {
            while (refusals.isEmpty()) {
                try {
                    accepted += write()
                } catch (refused: QuicCloseException) {
                    refusedAt = pair.now()
                    refusals += refused.circumstance
                }
                yield()
            }
            try {
                write()
            } catch (again: QuicCloseException) {
                refusedAgainAt = pair.now()
                refusals += again.circumstance
            }
        }

        private suspend fun write(): Int {
            chunk.position(0)
            chunk.setLimit(CHUNK)
            return stream.write(chunk, 1.hours).count
        }
    }

    /** Writes [size] bytes on [stream], passing the pair's loops until quiche took the last of them. */
    private suspend fun CoroutineScope.written(
        pair: VirtualPair,
        stream: QuicByteStream,
        size: Int,
    ) {
        var done = false
        launch {
            stream.writeFully(bytes(size), 1.hours)
            done = true
        }
        pair.runUntil { done }
    }

    private suspend fun readAll(
        stream: QuicByteStream,
        got: LongArray,
    ) {
        try {
            while (stream.read(1.hours) { got[0] += it.remaining() } is ScopedRead.Data<*>) Unit
        } catch (ended: QuicCloseException) {
            // The connection ended under the read, with nothing more held for it.
        }
    }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val MS = 1_000_000L
        const val WRITERS = 4
        const val CHUNK = 1_200
        const val PAYLOAD = 600
        const val BULK = 256 * 1024

        /** Less than a stream's flow window: written whole with nobody reading. */
        const val UNREAD = 16 * 1024
        const val APP_CODE = 9L
        val WRITING = 200.milliseconds
        val LIMIT = 10.seconds
        val SHORT_LIMIT = 2.seconds
        val CUT_AFTER = 100.milliseconds
    }
}
