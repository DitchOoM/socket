package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.DrainUntil
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A CONNECTION_CLOSE the link loses, on a real core client and server over a 60 ms round trip, on virtual time: the
 * closer repeats its close during its linger, so a peer with nothing in flight learns of the close within the repeats
 * instead of at its idle timeout; a peer with data in flight draws the close by its own retransmission.
 */
class LostCloseVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = IDLE, enablePmtuDiscovery = false)

    @Test
    fun aPeerWithNothingInFlightLearnsOfALostCloseFromItsRepeatNotItsIdleTimeout() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val asked = pair.now()
            pair.lose(End.Client, 1)
            var returned = false
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned = true
            }
            pair.runUntil { returned }
            assertEquals(1, pair.sentBy(End.Client, asked).size, "the client sent its close once, and the link lost it")
            pair.runUntil(within = IDLE * 2) { serverSide.end() is EdgeEnd.Ended }
            val took = pair.now() - asked
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.ApplicationError(APP_CODE), assertIs<ConnEnd.ByPeer>(end).error, "the server read a repeat of the close")
            assertTrue(took < 1.seconds.inWholeNanoseconds, "the server learned of the close ${took / MS} ms after it was sent")
        }

    @Test
    fun aPeerWithDataInFlightDrawsTheLostCloseByItsOwnRetransmission() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            launch { serverSide.openStream().writeFully(bytes(PAYLOAD), 1.hours) }
            val asked = pair.now()
            pair.lose(End.Client, 1)
            var returned = false
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned = true
            }
            pair.runUntil { returned }
            pair.runUntil(within = IDLE * 2) { serverSide.end() is EdgeEnd.Ended }
            val took = pair.now() - asked
            assertIs<ConnEnd.ByPeer>(assertIs<EdgeEnd.Ended>(serverSide.end()).end)
            assertTrue(took < 1.seconds.inWholeNanoseconds, "the server learned of the close ${took / MS} ms after it was sent")
        }

    @Test
    fun aCloseNothingAnswersIsRepeatedTwiceThenNeverAgainAndTheLingerStillEnds() =
        VirtualPair.with(options) { pair ->
            pair.accept()
            val asked = pair.now()
            var returned = false
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned = true
            }
            pair.runUntil { returned }
            pair.runUntil(within = 5.seconds) { !pair.client.scope.isActive }
            pair.runFor(IDLE)
            val sent = pair.sentBy(End.Client, asked)
            assertEquals(3, sent.size, "the close and two repeats: ${sent.map { (it.at - asked) / MS }} ms")
            for (repeat in sent.drop(1)) assertContentEquals(sent.first().bytes, repeat.bytes, "a repeat is the close itself")
        }

    @Test
    fun aCloseThePeersOwnCloseCrossesIsNotRepeatedUnprompted() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val asked = pair.now()
            var returned = 0
            launch {
                pair.client.connection.close(QuicError.ApplicationError(APP_CODE))
                returned++
            }
            launch {
                serverSide.close(QuicError.ApplicationError(APP_CODE))
                returned++
            }
            pair.runUntil { returned == 2 }
            pair.runUntil(within = 5.seconds) { !pair.client.scope.isActive }
            val arrivals = pair.sentBy(End.Server, asked).map { it.at + pair.oneWay.inWholeNanoseconds }.toSet()
            for (datagram in pair.sentBy(End.Client, asked).drop(1)) {
                assertTrue(datagram.at in arrivals, "every later datagram of the client answers one of the server's, none is unprompted")
            }
        }

    @Test
    fun aGracefulCloseWhoseLimitFallsDueWithNobodyInsideStillTellsThePeer() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            val stream = pair.client.connection.openStream()
            launch { stream.writeFully(bytes(PAYLOAD), 1.hours) }
            // The server's acknowledgements never arrive: the drain cannot finish, and the limit falls due on the loop.
            pair.lose(End.Server, Int.MAX_VALUE)
            val asked = pair.now()
            var outcome: CloseOutcome = CloseOutcome.Immediate
            var returned = false
            launch {
                outcome = pair.client.connection.close(CloseChoice.Graceful(LIMIT.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = true
            }
            pair.runUntil(within = LIMIT * 3) { returned && serverSide.end() is EdgeEnd.Ended }
            assertSame(CloseOutcome.LimitReached, outcome, "the limit ended the close")
            val end = assertIs<EdgeEnd.Ended>(serverSide.end()).end
            assertEquals(QuicError.NoError, assertIs<ConnEnd.ByPeer>(end).error, "the peer was told: the close at the limit reached it")
            assertTrue(pair.now() - asked >= LIMIT.inWholeNanoseconds, "told at the limit, not before")
        }

    private fun bytes(size: Int) =
        BufferFactory.Default.allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val MS = 1_000_000L
        const val APP_CODE = 4L
        const val PAYLOAD = 600
        val IDLE = 30.seconds
        val LIMIT = 500.milliseconds
    }
}
