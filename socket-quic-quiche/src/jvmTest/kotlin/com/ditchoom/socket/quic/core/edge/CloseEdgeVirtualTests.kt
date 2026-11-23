@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.FlowControl
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicCloseCircumstance
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicPathStats
import com.ditchoom.socket.quic.QuicSessionTicketState
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.core.life.CloseChoice
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.DrainUntil
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.read
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Closes at the edges of the close point, on a real core client and server over a 60 ms round trip, on virtual time:
 * the peer's close arriving mid-flush, a typed frame cut short, a datagram after the point, and a report of the
 * handshake that throws at the end.
 */
class CloseEdgeVirtualTests {
    private val options =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds, enablePmtuDiscovery = false)

    @Test
    fun aConnectionFlushingWhenThePeersCloseArrivesKeepsItsIdsThroughTheDrainAndSendsNothing() =
        VirtualPair.with(options) { pair ->
            val serverSide = pair.accept()
            var returned = false
            launch {
                pair.client.connection.close(CloseChoice.Graceful(10.seconds.inWholeNanoseconds, DrainUntil.PeerDone))
                returned = true
            }
            pair.runFor(10.milliseconds)
            assertEquals(false, returned, "the client flushes, waiting for the peer")
            launch { serverSide.close(QuicError.ApplicationError(APP_CODE)) }
            pair.runUntil { returned }
            val told = pair.now()
            assertIs<Life.Lingering>(
                pair.client.parts.conn
                    .life(),
                "the client keeps its ids while it drains",
            )
            pair.runFor(pair.oneWay * 3)
            assertIs<Life.Lingering>(
                pair.client.parts.conn
                    .life(),
                "the drain lasts three probe timeouts",
            )
            assertTrue(pair.sentBy(End.Client, told).isEmpty(), "a draining endpoint sends nothing")
        }

    @Test
    fun aTypedFrameCutShortAtTheClosePointResetsItsStream() =
        VirtualPair.with(
            options.copy(
                flowControl =
                    FlowControl(
                        initialMaxStreamDataBidiLocal = WINDOW.toLong(),
                        initialMaxStreamDataBidiRemote = WINDOW.toLong(),
                    ),
            ),
        ) { pair ->
            val serverSide = pair.accept()
            val typed = pair.client.connection.openTypedStream(NoteCodec)
            launch {
                try {
                    typed.send(Note(1, "x".repeat(WINDOW * 3)))
                } catch (refused: QuicCloseException) {
                    // Refused at the close point, the frame half sent.
                }
            }
            pair.runFor(200.milliseconds)
            var returned = false
            launch {
                pair.client.connection.close(CloseChoice.Graceful(10.seconds.inWholeNanoseconds, DrainUntil.Acknowledged))
                returned = true
            }
            pair.runUntil { returned }
            pair.runUntil { serverSide.end() is EdgeEnd.Ended }
            var last: ScopedReadResult<*> = ScopedReadResult.End
            val stream: QuicByteStream = serverSide.acceptStream()
            while (true) {
                last = stream.read(1.hours) { }
                if (last !is ScopedReadResult.Data<*>) break
            }
            assertIs<ScopedReadResult.Reset>(last, "the peer is told the frame was cut short, not handed half of it")
        }

    @Test
    fun aDatagramAfterTheClosePointIsRefusedNotSaidToBeSent() =
        VirtualPair.with(options.copy(datagrams = DatagramOptions())) { pair ->
            pair.accept()
            val channel = pair.client.connection.datagramChannel()
            launch { pair.client.connection.close(CloseChoice.Graceful(10.seconds.inWholeNanoseconds, DrainUntil.PeerDone)) }
            pair.runFor(10.milliseconds)
            var refusal: QuicCloseCircumstance = QuicCloseCircumstance.Unstated
            launch {
                refusal =
                    assertFailsWith<QuicCloseException> {
                        channel.send(
                            BufferFactory.Default
                                .allocate(8)
                                .apply { writeLong(1L) }
                                .also { it.resetForRead() },
                        )
                    }.circumstance
            }
            pair.runUntil { refusal != QuicCloseCircumstance.Unstated }
            assertEquals<QuicCloseCircumstance>(QuicCloseCircumstance.Ended, refusal)
        }

    @Test
    fun aReportOfTheHandshakeThatThrowsAtTheEndStillPublishesTheEnd() =
        VirtualPair.with(options, start = PairStart.Started, clientApi = { StatsFailWhileDraining(it) }) { pair ->
            // The server's flight that completes the client's handshake and the server's close reach the client together.
            pair.hold(End.Server)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            val flight = pair.heldFrom(End.Server)
            pair.server.parts.beginShutdown()
            pair.runUntil { pair.heldFrom(End.Server) > flight }
            pair.release(End.Server)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertIs<ConnEnd.ProcessingFailed>(assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
        }

    @Test
    fun aSessionTicketThatArrivesWithThePeersCloseIsKept() =
        VirtualPair.with(options.copy(enableEarlyData = true), start = PairStart.Started) { pair ->
            pair.runUntil { pair.client.connection.state.value is QuicConnectionState.Established }
            // What the server sends once its handshake completes, its ticket among it, and its close arrive together.
            pair.hold(End.Server)
            var accepted: CoreConnection? = null
            launch { accepted = pair.accept() }
            pair.runUntil { accepted != null && pair.heldFrom(End.Server) > 0 }
            val serverSide = checkNotNull(accepted)
            pair.runFor(5.milliseconds)
            val flight = pair.heldFrom(End.Server)
            var closed = false
            launch {
                serverSide.close(QuicError.ApplicationError(APP_CODE))
                closed = true
            }
            pair.runUntil { closed }
            assertTrue(pair.heldFrom(End.Server) > flight, "the server's close is held behind its ticket")
            pair.release(End.Server)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended }
            assertIs<QuicSessionTicketState.Issued>(pair.client.connection.sessionTicket.value, "the ticket was kept")
            assertIs<ConnEnd.ByPeer>(assertIs<EdgeEnd.Ended>(pair.client.connection.end()).end)
        }

    /** quiche's path statistics fail once quiche drains: the report of a handshake that completed in the closing batch throws. */
    private class StatsFailWhileDraining(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connPathStats(
            conn: QuicheConn,
            pathIdx: Long,
        ): QuicPathStats? {
            if (inner.connIsDraining(conn)) throw IllegalStateException()
            return inner.connPathStats(conn, pathIdx)
        }
    }

    private companion object {
        const val APP_CODE = 6L
        const val WINDOW = 16 * 1024
    }
}
