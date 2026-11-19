package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseLinger
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.ScopedRead
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.time.RttPrior
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A connection's close on a real core client and server over a 60 ms round trip, on virtual time: [close] returns once
 * its CONNECTION_CLOSE is sent, the connection lingers for quiche's draining time answering late packets with that same
 * datagram and handing none to quiche, and a graceful close delivers what was written before it closes.
 */
class CloseLingerVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun closeReturnsInThePassThatSentItsConnectionCloseNotAfterTheDrainingTime() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val asked = pair.now()
            var returned = -1L
            launch {
                pair.client.connection.close(QuicError.NoError)
                returned = pair.now()
            }
            pair.runUntil { returned >= 0 }
            val close = pair.sentBy(End.Client, asked)
            assertTrue(close.isNotEmpty(), "the client sent its CONNECTION_CLOSE")
            assertEquals(asked, close.first().at, "the CONNECTION_CLOSE left when close() was asked")
            assertTrue(
                returned - asked < RTT_NANOS,
                "close() returned ${(returned - asked) / 1_000_000} ms after it was asked: it must not wait out three PTOs",
            )
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByPeer>(end).error, "the server read the client's close")
        }

    @Test
    fun aLatePacketDuringTheLingerIsAnsweredWithTheSameCloseAndNeverReachesQuiche() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            // The server sends; the client closes before that datagram, a half round trip away, arrives.
            launch { serverSide.openStream().writeFully(bytes(PAYLOAD), 1.hours) }
            pair.runFor(1.milliseconds)
            val serverSent = pair.sentBy(End.Server).size
            assertTrue(serverSent > 0)
            val asked = pair.now()
            val recvsBefore = pair.clientRecvs
            var returned = false
            launch {
                pair.client.connection.close(QuicError.NoError)
                returned = true
            }
            pair.runUntil { returned }
            pair.runFor(120.milliseconds)
            val after = pair.sentBy(End.Client, asked)
            assertTrue(after.size >= 2, "the late datagram was answered: the client sent ${after.size} datagram(s) after close()")
            val close = after.first()
            for (answer in after.drop(1)) {
                assertContentEquals(close.bytes, answer.bytes, "every answer is the CONNECTION_CLOSE itself")
                assertTrue(answer.at >= asked + RTT_NANOS / 2 - 1_000_000, "answered when the late datagram arrived")
            }
            assertEquals(recvsBefore, pair.clientRecvs, "no datagram reached the client's quiche after its close")
        }

    @Test
    fun theLingerLastsQuichesDrainingTimeEvenWithAShorterIdleTimeoutAndTheEndStaysTheClose() {
        val short = options.copy(idleTimeout = 100.milliseconds)
        VirtualPair.with(short) { pair ->
            pair.accept()
            val asked = pair.now()
            var returned = false
            launch {
                pair.client.connection.close(QuicError.NoError)
                returned = true
            }
            pair.runUntil { returned }
            pair.runFor(60.milliseconds)
            assertTrue(pair.client.scope.isActive, "the client still lingers a round trip after its close")
            pair.runUntil(within = 5.seconds) { !pair.client.scope.isActive }
            val lingered = pair.now() - asked
            assertTrue(lingered >= 3 * RTT_NANOS, "the linger lasted ${lingered / 1_000_000} ms: three PTOs are at least three round trips")
            val end = assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(end).error, "the idle timeout did not replace the close's end")
        }
    }

    @Test
    fun aGracefulCloseDeliversEverythingWrittenBeforeItsConnectionCloseEvenWhenSomeWasLost() =
        VirtualPair.with(options.copy(closeLinger = QuicCloseLinger.UntilAcknowledged)) { pair ->
            val serverSide = pair.accept()
            val got = IntArray(1)
            launch { readAll(serverSide, got) }
            var written = -1L
            var returned = -1L
            var gotWhenReturned = -1
            launch {
                pair.client.connection
                    .openStream()
                    .writeFully(bytes(BULK), 1.hours)
                written = pair.now()
                assertTrue(pair.loseInFlight(End.Client) > 0, "the last of the data is lost on the way")
                pair.client.connection.close(QuicError.NoError)
                returned = pair.now()
                gotWhenReturned = got[0]
            }
            pair.runUntil { returned >= 0 }
            assertTrue(returned > written, "the close waited for what was written to be acknowledged")
            assertEquals(BULK, gotWhenReturned, "the peer had read every byte, the lost ones resent, before the close went out")
        }

    @Test
    fun aCloseWithAnErrorSendsAtOnceAndAbandonsWhatWasNotDelivered() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val got = IntArray(1)
            launch { readAll(serverSide, got) }
            var written = -1L
            var returned = -1L
            launch {
                pair.client.connection
                    .openStream()
                    .writeFully(bytes(BULK), 1.hours)
                written = pair.now()
                assertTrue(pair.loseInFlight(End.Client) > 0, "the last of the data is lost on the way")
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned = pair.now()
            }
            pair.runUntil { returned >= 0 }
            assertEquals(written, returned, "an aborting close returns in the pass that sent it")
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            assertTrue(got[0] < BULK, "the lost bytes were never resent: ${got[0]} of $BULK arrived")
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByPeer>(end).error)
        }

    @Test
    fun aCloseAskedInTheInstantTheSocketMovesEndsByTheCloseAndThePeerReadsIt() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            pair.client.parts.migrate(pair.client.local, RttPrior.CONFIGURED)
            var returned = false
            launch {
                pair.client.connection.close(QuicError.NoError)
                returned = true
            }
            pair.runUntil { returned }
            val end = assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(end).error)
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            val peer = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByPeer>(peer).error, "the server read the close from the moved socket")
        }

    @Test
    fun aSocketMovedWhileTheConnectionLingersChangesNothing() =
        VirtualPair.with(options) { pair ->
            pair.accept()
            var returned = false
            launch {
                pair.client.connection.close(QuicError.NoError)
                returned = true
            }
            pair.runUntil { returned }
            pair.client.parts.migrate(pair.client.local, RttPrior.CONFIGURED)
            pair.runUntil(within = 5.seconds) { !pair.client.scope.isActive }
            val end = assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByLocal>(end).error)
        }

    private suspend fun readAll(
        connection: CoreConnection,
        got: IntArray,
    ) {
        try {
            val stream = connection.acceptStream()
            while (stream.read(1.hours) { got[0] += it.remaining() } is ScopedRead.Data<*>) Unit
        } catch (ended: Exception) {
            // The connection ended under the read: what arrived is counted.
        }
    }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val RTT_NANOS = 60_000_000L
        const val PAYLOAD = 600
        const val BULK = 64 * 1024
        const val APP_CODE = 7L
    }
}
