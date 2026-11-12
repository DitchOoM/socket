package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How often the loop flushes while it reads a run of received datagrams, which is how often quiche writes
 * an ACK ([AckCadence]): every second datagram on a path that has shown no loss, every datagram once it has.
 *
 * Each test queues its datagrams before the loop runs, so they are one turn, and reads the order of the
 * loop's calls into quiche at the end of that turn (the loop asks quiche for its timer first thing after
 * it), never during it.
 */
class DriverAckCadenceTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** The calls the loop makes that this test orders, and the loss counter quiche reports. */
    private class ScriptedApi(
        private val stub: StubQuicheApi,
        /** What quiche reports as lost: fixed before the loop starts. */
        private val lost: Long,
        /** What quiche reports as packets received after a gap: fixed before the loop starts. */
        private val gaps: Long,
    ) : QuicheApi by stub {
        val calls = Channel<String>(Channel.UNLIMITED)

        override fun connLossEvidence(conn: QuicheConn): Long = lost

        override fun connRecvGaps(conn: QuicheConn): Long = gaps

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int {
            calls.trySend(RECEIVED)
            return stub.connRecv(conn, buf, bufLen, recvInfo)
        }

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            calls.trySend(SENT)
            return stub.connSend(conn, buf, bufLen, sendInfo)
        }

        override fun connTimeout(conn: QuicheConn): Duration? {
            calls.trySend(TURN_ENDED)
            return stub.connTimeout(conn)
        }
    }

    /**
     * Queue [datagrams] received datagrams and run them as one turn, on a connection quiche reports
     * [lost] lost packets for. The turn as a string: `R` per datagram handed to quiche, `F` per flush
     * (a run of sends is one flush).
     */
    private suspend fun CoroutineScope.turnOf(
        datagrams: Int,
        lost: Long,
        gaps: Long = 0,
    ): String {
        val stub = StubQuicheApi()
        stub.pathStats = listOf(pathStats(rtt = RTT))
        val api = ScriptedApi(stub, lost, gaps)
        val driver =
            QuicheDriver(
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
        repeat(datagrams) {
            driver.commands.send(
                QuicheCmd.RecvPacket(
                    bufferFactory.allocate(
                        PACKET_BYTES,
                    ),
                    PACKET_BYTES,
                    PacketSource.FromServerSocket(QuicheRecvInfo(1L)) {
                    },
                ),
            )
        }
        driver.start(this)
        try {
            val turn = StringBuilder()
            withTimeout(5.seconds) {
                // The turn runs from its first datagram to the loop asking quiche for its timer.
                while (api.calls.receive() != RECEIVED) Unit
                turn.append('R')
                var received = 1
                while (true) {
                    when (api.calls.receive()) {
                        RECEIVED -> {
                            turn.append('R')
                            received++
                        }
                        SENT -> if (turn.last() != 'F') turn.append('F')
                        TURN_ENDED -> if (received == datagrams) return@withTimeout
                    }
                }
            }
            return turn.toString()
        } finally {
            driver.destroy()
        }
    }

    @Test
    fun aPathThatHasShownNoLossAcksEverySecondDatagram() =
        runQuicTest {
            assertEquals("RRFRRF", turnOf(datagrams = 4, lost = 0))
        }

    @Test
    fun aReceiverThatSawGapsInThePacketNumbersAcksEveryDatagramWithoutAnyLossOfItsOwn() =
        runQuicTest {
            assertEquals("RFRFRFRF", turnOf(datagrams = 4, lost = 0, gaps = 2))
        }

    @Test
    fun aLoneGapInThePacketNumbersIsNotYetEnoughToAckEveryDatagram() =
        runQuicTest {
            assertEquals("RRFRRF", turnOf(datagrams = 4, lost = 0, gaps = 1))
        }

    @Test
    fun aPathThatHasShownLossAcksEveryDatagram() =
        runQuicTest {
            assertEquals("RFRFRFRF", turnOf(datagrams = 4, lost = 3))
        }

    private fun pathStats(rtt: Duration) =
        QuicPathStats(
            validationState = 0,
            active = true,
            recv = 0,
            sent = 0,
            lost = 0,
            retrans = 0,
            totalPtoCount = 0,
            rtt = rtt,
            minRtt = rtt,
            maxRtt = rtt,
            rttvar = Duration.ZERO,
            cwnd = 0,
            sentBytes = 0,
            recvBytes = 0,
            lostBytes = 0,
            streamRetransBytes = 0,
            pmtu = 0,
            deliveryRate = 0,
        )

    private companion object {
        const val RECEIVED = "connRecv"
        const val SENT = "connSend"
        const val TURN_ENDED = "connTimeout"
        const val PACKET_BYTES = 64
        val RTT = 100.milliseconds
    }
}
