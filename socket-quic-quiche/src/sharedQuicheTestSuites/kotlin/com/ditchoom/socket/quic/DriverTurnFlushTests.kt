package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What quiche is asked to send must leave in the turn that asked for it, not in whichever turn happens
 * to come next.
 *
 * quiche queues MAX_STREAM_DATA / MAX_DATA credit when the application consumes stream bytes, and
 * NEW_CONNECTION_ID when a CID is issued. None of that arms a quiche timer, so frames queued after a
 * turn's flush wait for the next command or timer, however long that is. Each test records the order of
 * the driver's calls into quiche and requires a `connSend` after the call that queued something.
 */
class DriverTurnFlushTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Every call the loop makes that this test orders, in order. */
    private class RecordingApi(
        private val stub: StubQuicheApi,
    ) : QuicheApi by stub {
        /**
         * Written by the loop, read by the test on another thread: a channel, not a list. `connTimeout` is
         * the first thing the loop calls after a turn, so it marks where the turn ended.
         */
        val calls = Channel<String>(Channel.UNLIMITED)

        override fun connTimeout(conn: QuicheConn): Duration? {
            calls.trySend("connTimeout")
            return stub.connTimeout(conn)
        }

        override fun connStreamRecv(
            conn: QuicheConn,
            streamId: QuicStreamId,
            buf: Long,
            bufLen: Int,
        ): StreamRecvResult {
            calls.trySend("connStreamRecv")
            return stub.connStreamRecv(conn, streamId, buf, bufLen)
        }

        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int {
            calls.trySend("connSend")
            return stub.connSend(conn, buf, bufLen, sendInfo)
        }
    }

    @Test
    fun bytesTheLoopConsumesForAParkedReadAreFollowedByAFlushInTheSameTurn() =
        runQuicTest {
            val stub = StubQuicheApi()
            stub.streamRecvResult = StreamRecvResult.Done
            val api = RecordingApi(stub)
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val opened = CompletableDeferred<StreamSlot>()
                driver.commands.send(QuicheCmd.OpenStream(opened))
                val slot = withTimeout(2.seconds) { opened.await() }

                slot.advertiseWaitingRead(READ_SIZE)
                stub.readableStreams.addLast(slot.id.id)
                stub.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 8, fin = false))
                while (api.calls.tryReceive().isSuccess) Unit // earlier turns' calls are not under test
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))

                // The slot's signal fires from inside the turn, before the turn's last flush, so it is not
                // where to look. Read the loop's calls until the turn ends (it asks quiche for its timer),
                // starting from the call that consumed the bytes.
                val afterConsume = mutableListOf<String>()
                withTimeout(2.seconds) {
                    while (api.calls.receive() != "connStreamRecv") Unit
                    while (true) {
                        val call = api.calls.receive()
                        if (call == "connTimeout") break
                        afterConsume += call
                    }
                }
                assertTrue(
                    afterConsume.contains("connSend"),
                    "the loop consumed stream bytes (quiche now owes the peer flow-control credit) and ended the turn " +
                        "without a flush; the credit waits for the next wake: $afterConsume",
                )
            } finally {
                driver.destroy()
            }
        }

    /**
     * A send quiche rejects closes the connection from inside the flush, the first phase of the turn. A
     * stream that became readable in that same turn has not been looked at yet, so the close has no slot
     * to drain its bytes into and reports `AllRead` while quiche still holds them.
     */
    @Test
    fun aStreamReadableInTheTurnASendRejectionClosesIsStillReportedUnread() =
        runQuicTest {
            val stub = StubQuicheApi()
            stub.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 5, fin = false))
            stub.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(RecordingApi(stub))
            driver.start(this)
            try {
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Established } }
                stub.readableStreams.addLast(1L)
                stub.connSendQueue += -6 // rejected…
                stub.connSendQueue += -6 // …and rejected again after the close: the connection is gone
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }

                assertEquals(
                    QuicUnreadAtClose.Unread(setOf(QuicStreamId(1L))),
                    driver.unreadAtClose.value,
                    "the stream held 5 bytes when the connection closed, in the same turn it became readable",
                )
            } finally {
                driver.destroy()
            }
        }

    private fun createTestDriver(api: QuicheApi): QuicheDriver =
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
        const val READ_SIZE = 1024
    }
}
