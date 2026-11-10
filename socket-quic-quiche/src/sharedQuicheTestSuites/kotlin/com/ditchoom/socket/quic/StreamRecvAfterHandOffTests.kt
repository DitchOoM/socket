package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * **A read's `StreamRecv` that reaches the driver after the driver has handed the stream's end to the
 * read parked on it.**
 *
 * The driver loop reads quiche for a parked read when the stream turns readable. When what quiche had was
 * only the stream's end (a FIN with no bytes, or a RESET_STREAM), quiche has collected the stream, the
 * hand-off queued nothing, and the stream's end is latched. A `StreamRecv` already on its way from that
 * read must be answered `Done`, so the read reports the latched end. Asking quiche about the collected
 * stream answers `InvalidStreamState` (-7).
 */
class StreamRecvAfterHandOffTests {
    private val bufferFactory = BufferFactory.deterministic()

    private val resetCode = QuicAppErrorCode(0x10c)

    @Test
    fun aPureFinHandedToAParkedReadIsTheAnswerToItsLaterStreamRecv() =
        runQuicTest {
            val handedOff = StreamRecvResult.Data(bytesRead = 0, fin = true)
            val slot = streamRecvAfterHandOff(handedOff) { assertIs<StreamRecvResult.Done>(it) }
            assertEquals(StreamEnd.Fin, slot.end)
            assertIs<ReadResult.End>(slot.queuedOrLatchedVerdict { null })
        }

    @Test
    fun aResetHandedToAParkedReadIsTheAnswerToItsLaterStreamRecv() =
        runQuicTest {
            val slot = streamRecvAfterHandOff(StreamRecvResult.Reset(resetCode)) { assertIs<StreamRecvResult.Done>(it) }
            assertEquals(StreamEnd.Reset(resetCode), slot.end)
            assertIs<ReadResult.Reset>(slot.queuedOrLatchedVerdict { null })
        }

    /**
     * Parks a read on a stream, lets the driver loop hand it [handedOff] from quiche, then delivers a
     * `StreamRecv` for that stream to a quiche that answers `InvalidStreamState` for it, as quiche does
     * once it has collected a stream. [check] judges the answer.
     */
    private suspend fun CoroutineScope.streamRecvAfterHandOff(
        handedOff: StreamRecvResult,
        check: (StreamRecvResult) -> Unit,
    ): StreamSlot {
        val api = StubQuicheApi()
        api.streamRecvResult = StreamRecvResult.Error(QUICHE_ERR_INVALID_STREAM_STATE)
        val driver = createTestDriver(api)
        driver.start(this)
        try {
            val opened = CompletableDeferred<StreamSlot>()
            driver.commands.send(QuicheCmd.OpenStream(opened))
            val slot = withTimeout(2.seconds) { opened.await() }

            slot.advertiseWaitingRead(READ_SIZE)
            api.readableStreams.addLast(slot.id.id)
            api.streamRecvSequence.addLast(handedOff)
            driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))
            withTimeout(2.seconds) { slot.dataSignal.receive() } // the hand-off is done: the end is latched

            val buf = bufferFactory.allocate(READ_SIZE)
            val answer = CompletableDeferred<StreamRecvResult>()
            driver.commands.send(QuicheCmd.StreamRecv(slot.id.id, buf.driverOwnedMemory(), READ_SIZE, answer))
            check(withTimeout(2.seconds) { answer.await() })
            buf.freeNativeMemory()
            return slot
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
        const val QUICHE_ERR_INVALID_STREAM_STATE = -7
    }
}
