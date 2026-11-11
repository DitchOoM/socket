@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Two decisions the datagram send path makes about quiche's state: when a sender refused for a full
 * queue is told to try again, and whether a datagram fits.
 */
class DriverDatagramTurnTests {
    private val bufferFactory = BufferFactory.deterministic()
    private val testPeer = SocketAddress.ofLiteral("127.0.0.1", 4433)

    /** Counts the datagram sends quiche is asked to accept; after [limit] of them it accepts, to end a spin. */
    private class CountingApi(
        private val stub: StubQuicheApi,
        private val limit: Int,
    ) : QuicheApi by stub {
        @kotlin.concurrent.Volatile
        var sends = 0

        override fun connDgramSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
        ): Int {
            if (++sends >= limit) stub.connDgramSendResult = null
            return stub.connDgramSend(conn, buf, bufLen)
        }
    }

    private fun payload(len: Int) =
        bufferFactory.allocate(len).apply {
            repeat(len) { writeByte(0) }
            resetForRead()
        }

    /**
     * A sender refused because quiche's datagram queue is full must wait until something drains it. The
     * queue drains only when a flush sends a packet, and here nothing is ever sent (the congestion window
     * is closed). A writer woken anyway re-sends at once, is refused again, and the loop and the sender
     * trade commands for as long as the window stays closed.
     */
    @Test
    fun aSenderRefusedForAFullQueueWaitsUntilAFlushSendsSomething() =
        runQuicTest {
            val stub = StubQuicheApi()
            stub.dgramMaxWritableLen = MaxDatagramSize.Bytes(1200)
            stub.connDgramSendResult = QuicheDriver.QUICHE_ERR_DONE // the queue is full
            val api = CountingApi(stub, limit = SPIN_LIMIT)
            val driver = driverWith(api)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                val sender = async { adapter.send(payload(16)) }
                // A spinning pair reaches the limit in well under a millisecond; a waiting sender never does.
                withTimeoutOrNull(SPIN_PATIENCE) { while (api.sends < SPIN_LIMIT) yield() }
                val sends = api.sends
                assertTrue(
                    sends < SPIN_LIMIT,
                    "the loop and a refused sender traded $sends DgramSend commands with nothing sent in between: " +
                        "the sender was woken for a queue that had not drained",
                )
                assertTrue(sender.isActive, "the sender must still be waiting for room")
                // Room opens: a flush sends a packet, and the sender is woken to retry.
                stub.connDgramSendResult = null
                stub.connSendQueue += 1200
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))
                withTimeout(2.seconds) { sender.await() }
            } finally {
                driver.destroy()
            }
        }

    /**
     * The adapter's check against the cached maximum is check-then-act: the path moved between the cache
     * refresh and the send. quiche decides at the send, so the answer has to come from there: a datagram
     * that no longer fits is the caller's argument error, and the connection is still open.
     */
    @Test
    fun aDatagramThatNoLongerFitsAfterThePathShrankIsAnArgumentErrorNotACloseOfAnOpenConnection() =
        runQuicTest {
            val stub = StubQuicheApi()
            stub.dgramMaxWritableLen = MaxDatagramSize.Bytes(1200)
            val driver = driverWith(stub)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                withTimeout(2.seconds) { awaitMaxSizePublished(driver) }
                stub.dgramMaxWritableLen = MaxDatagramSize.Bytes(100) // the path moved; no turn has refreshed the cache yet
                assertFailsWith<IllegalArgumentException> { adapter.send(payload(500)) }
                assertIs<QuicConnectionState.Established>(driver.state.value, "the connection is open: only the datagram was refused")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun aDatagramThatFitsAfterThePathGrewIsSentNotRefusedAgainstAStaleMaximum() =
        runQuicTest {
            val stub = StubQuicheApi()
            stub.dgramMaxWritableLen = MaxDatagramSize.Bytes(100)
            val driver = driverWith(stub)
            val adapter = DriverDatagramAdapter(driver, testPeer)
            driver.start(this)
            try {
                withTimeout(2.seconds) { awaitMaxSizePublished(driver) }
                stub.dgramMaxWritableLen = MaxDatagramSize.Bytes(1200)
                withTimeout(2.seconds) { adapter.send(payload(500)) }
            } finally {
                driver.destroy()
            }
        }

    private suspend fun awaitMaxSizePublished(driver: QuicheDriver) {
        while (driver.lastMaxDatagramSize !is MaxDatagramSize.Bytes) yield()
    }

    private fun driverWith(api: QuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: these tests never move a path.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            role = QuicRole.Client,
            ingress = DatagramIngress.ExternalPump,
        )

    private companion object {
        const val SPIN_LIMIT = 500
        val SPIN_PATIENCE = 300.milliseconds
    }
}
