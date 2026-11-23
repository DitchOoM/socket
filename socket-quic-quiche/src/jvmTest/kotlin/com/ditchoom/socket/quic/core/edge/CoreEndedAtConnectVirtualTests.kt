package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumptionOutcome
import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.QuicUnreadAtClose
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.ScopedReadResult
import com.ditchoom.socket.quic.ServerCertVerdict
import com.ditchoom.socket.quic.ServerCertVerifier
import com.ditchoom.socket.quic.core.conn.HandshakeFate
import com.ditchoom.socket.quic.core.life.CloseOutcome
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.read
import com.ditchoom.socket.quic.runUntilClosed
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A connection whose handshake completed is handed over by the connect whatever ended it after, on a real core client
 * and server on virtual time, through the connect's own [ServerCheck.connected]: whether the peer's close lands before
 * the connect returns, with the flight that completes the handshake, or after, the caller sees the same: its first
 * operation reports the peer's close with the peer's code, what the handshake settled stays readable, and the server is
 * checked against the certificates the handshake copied, so a trusted one is returned and an untrusted one still fails
 * the connect. A handshake that never completed fails it.
 */
class CoreEndedAtConnectVirtualTests {
    private fun options(verifier: ServerCertVerifier) =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            serverCertVerifiers = listOf(verifier),
        )

    /** Whether the peer's close is served before the caller's connect returns, or after. */
    private enum class Order { CloseFirst, ConnectFirst }

    /** What a caller sees of a connection whose server closed it at once. */
    private data class Seen(
        val connect: String,
        val open: QuicCloseReason,
        val accept: QuicCloseReason,
        val alpn: String,
        val resumption: QuicResumptionOutcome,
        val verifierCalls: Int,
        val leafBytes: Int,
        val stats: QuicStatsRead,
        val outcome: CloseOutcome,
    )

    @Test
    fun aServerThatClosesAtOnceIsSeenTheSameWhicheverLandsFirstTheCloseOrTheConnect() {
        val closeFirst = seen(Order.CloseFirst)
        val connectFirst = seen(Order.ConnectFirst)
        assertEquals(connectFirst, closeFirst, "the order of the close and the connect's return changes nothing the caller sees")
        assertEquals(RETURNED, closeFirst.connect)
        assertEquals<QuicCloseReason>(QuicCloseReason.ByPeer(QuicError.ApplicationError(APP_CODE)), closeFirst.open)
        assertEquals("test", closeFirst.alpn)
        assertEquals(1, closeFirst.verifierCalls, "the server was checked once, before the connect returned")
        assertTrue(closeFirst.leafBytes > 0, "the verifier read the server's leaf, copied when the handshake completed")
        assertEquals<QuicStatsRead>(QuicStatsRead.Ended, closeFirst.stats, "statistics asked after the end report the end")
        assertEquals<CloseOutcome>(CloseOutcome.NotAsked, closeFirst.outcome, "the caller never asked for a close")
    }

    @Test
    fun aTrustedServerWhoseFlightAndCloseArriveInOneBatchIsReturnedByTheConnect() =
        oneBatch(ServerCertVerdict.Trusted) { pair, connect ->
            val client = pair.client.connection
            assertEquals(RETURNED, connect.outcome)
            val ended = assertIs<EdgeEnd.Ended>(client.end())
            assertIs<HandshakeFate.Completed>(ended.handshake)
            assertEquals<CloseOutcome>(CloseOutcome.NotAsked, ended.outcome, "the caller never asked for a close")
            val reason = assertFailsWith<QuicCloseException> { client.openStream() }.closeReason
            assertIs<QuicCloseReason.ByPeer>(reason, "the server's close, reported by the first operation")
            assertEquals("test", client.negotiatedAlpn)
        }

    @Test
    fun anUntrustedServerWhoseFlightAndCloseArriveInOneBatchStillFailsTheConnect() =
        oneBatch(ServerCertVerdict.Rejected("not ours")) { _, connect ->
            assertTrue(connect.outcome.startsWith(REFUSED), "the verification's failure, not the close: ${connect.outcome}")
        }

    @Test
    fun aHandshakeThatNeverCompletedStillFailsTheConnect() {
        val options = options(RecordingVerifier(ServerCertVerdict.Trusted))
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            pair.hold(End.Server)
            val client = pair.client.connection
            val connect = connectOf(pair.client, options)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            client.closeNow(QuicError.NoError)
            pair.runUntil { connect.done() }
            assertTrue(connect.outcome.startsWith(FAILED), connect.outcome)
            assertIs<HandshakeFate.NotCompleted>(assertIs<EdgeEnd.Ended>(client.end()).handshake)
        }
    }

    @Test
    fun aChainCopyThatThrowsEndsTheConnectionAndFailsTheConnect() {
        val options = options(RecordingVerifier(ServerCertVerdict.Trusted))
        VirtualPair.with(options, start = PairStart.Started, clientApi = { ChainFails(it) }) { pair ->
            val client = pair.client.connection
            val connect = connectOf(pair.client, options)
            pair.runUntil { connect.done() && client.end() is EdgeEnd.Ended }
            val ended = assertIs<EdgeEnd.Ended>(client.end())
            assertIs<ConnEnd.ProcessingFailed>(ended.end)
            assertIs<HandshakeFate.NotCompleted>(ended.handshake, "a completion whose report failed was never reported")
            assertTrue(connect.outcome.startsWith(FAILED), connect.outcome)
        }
    }

    @Test
    fun aBlockRunUntilTheCloseOfAConnectionTheServerClosedBeforeTheConnectReturnedEndsAtOnceAllRead() {
        val options = options(RecordingVerifier(ServerCertVerdict.Trusted))
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            val client = pair.client.connection
            launch { pair.accept().close(QuicError.NoError) }
            pair.runUntil { client.end() is EdgeEnd.Ended }
            val connect = connectOf(pair.client, options)
            pair.runUntil { connect.done() }
            assertEquals(RETURNED, connect.outcome, "the close landed before the connect looked")
            var closed: QuicCloseException? = null
            launch { closed = assertFailsWith<QuicCloseException> { client.runUntilClosed(LINGER) { awaitCancellation() } } }
            val began = pair.now()
            pair.runUntil { closed != null }
            assertIs<QuicCloseReason.ByPeer>(checkNotNull(closed).closeReason)
            assertTrue(pair.now() - began < LINGER.inWholeNanoseconds / 4, "the block ended at once, not at the linger")
            assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, client.unreadAtClose.value)
        }
    }

    @Test
    fun streamDataHeldAtACloseThatLandedBeforeTheConnectReturnedIsReadAfterIt() {
        val options = options(RecordingVerifier(ServerCertVerdict.Trusted))
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            val client = pair.client.connection
            launch {
                val server = pair.accept()
                server.openStream().writeFully(
                    BufferFactory.Default
                        .allocate(HELD)
                        .also { it.position(HELD) }
                        .also { it.resetForRead() },
                    1.hours,
                )
                server.close(QuicError.NoError)
            }
            pair.runUntil { client.end() is EdgeEnd.Ended }
            val connect = connectOf(pair.client, options)
            pair.runUntil { connect.done() }
            assertEquals(RETURNED, connect.outcome, "the close landed before the connect looked")
            var got = -1
            launch {
                val stream = client.acceptStream()
                var read = 0
                try {
                    while (stream.read(1.hours) { read += it.remaining() } is ScopedReadResult.Data<*>) Unit
                } catch (closed: QuicCloseException) {
                    // The server never finished the stream: after what was held, the read meets the close.
                }
                got = read
            }
            pair.runUntil { got >= 0 }
            assertEquals(HELD, got, "what the end held was read after the connect returned")
            assertEquals<QuicUnreadAtClose>(QuicUnreadAtClose.AllRead, client.unreadAtClose.value)
        }
    }

    /** quiche's chain export fails: the copy taken when the handshake completes throws. */
    private class ChainFails(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connPeerCertChainAt(
            conn: QuicheConn,
            index: Int,
            buf: Long,
            bufLen: Int,
        ): Int = error("no chain export")
    }

    /** The server's flight that completes the client's handshake and the server's close reach the client together. */
    private fun oneBatch(
        verdict: ServerCertVerdict,
        check: suspend (VirtualPair, Connect) -> Unit,
    ) {
        val options = options(RecordingVerifier(verdict))
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            pair.hold(End.Server)
            pair.runUntil { pair.heldFrom(End.Server) > 0 }
            val flight = pair.heldFrom(End.Server)
            pair.server.parts.beginShutdown()
            pair.runUntil { pair.heldFrom(End.Server) > flight }
            pair.release(End.Server)
            val connect = connectOf(pair.client, options)
            pair.runUntil { pair.client.connection.end() is EdgeEnd.Ended && connect.done() }
            check(pair, connect)
        }
    }

    private fun seen(order: Order): Seen {
        val verifier = RecordingVerifier(ServerCertVerdict.Trusted)
        val options = options(verifier)
        var seen: Seen? = null
        VirtualPair.with(options, start = PairStart.Started) { pair ->
            val client = pair.client.connection
            val early =
                when (order) {
                    Order.ConnectFirst -> connectOf(pair.client, options).also { pair.runUntil { it.done() } }
                    Order.CloseFirst -> null
                }
            var serverClosed = false
            launch {
                pair.accept().close(QuicError.ApplicationError(APP_CODE))
                serverClosed = true
            }
            pair.runUntil { serverClosed && client.end() is EdgeEnd.Ended }
            val connect = early ?: connectOf(pair.client, options).also { pair.runUntil { it.done() } }
            var stats: QuicStatsRead? = null
            launch { stats = client.stats() }
            pair.runUntil { stats != null }
            seen =
                Seen(
                    connect = connect.outcome,
                    open = assertFailsWith<QuicCloseException> { client.openStream() }.closeReason,
                    accept = assertFailsWith<QuicCloseException> { client.acceptStream() }.closeReason,
                    alpn = client.negotiatedAlpn,
                    resumption = client.resumption,
                    verifierCalls = verifier.calls,
                    leafBytes = verifier.leafBytes,
                    stats = checkNotNull(stats),
                    outcome = assertIs<EdgeEnd.Ended>(client.end()).outcome,
                )
        }
        return checkNotNull(seen)
    }

    private companion object {
        const val APP_CODE = 0x2AL
        const val HELD = 4096
        val LINGER = 5.seconds
    }
}
