package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A stream read on a connection that ended WITHOUT a peer FIN for that stream throws the typed
 * [QuicCloseException], never [ReadResult.End]: End means the peer sent FIN, and a local idle timeout
 * is not that. Bytes drained out of quiche at teardown are still delivered first.
 *
 * Virtual time ([runTest] + [RealDriverClock] + `EmptyCoroutineContext`), so the idle timer costs no
 * wall-clock time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnIdleCloseIsTypedTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Reader parked on dataSignal when the idle timer closes the connection. */
    @Test
    fun aReadParkedWhenTheIdleTimerClosesTheConnectionThrowsTheTypedClose() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            api.closeOnTimeout = true
            api.streamRecvResult = StreamRecvResult.Done // nothing readable, no FIN -> the reader parks
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                runCurrent()
                val slot = openStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                val read = async { runCatching { adapter.streamRead(slot.id, bufferFactory, 1024, 30.seconds) } }
                runCurrent()
                assertTrue(read.isActive, "reader should be parked — nothing has been made readable")

                testScheduler.advanceTimeBy(2.seconds)
                runCurrent()

                val state = assertIs<QuicConnectionState.Closed>(driver.state.value, "idle timer never closed the connection")
                assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), state.reason)

                val outcome = read.await()
                val thrown =
                    assertIs<QuicCloseException>(
                        outcome.exceptionOrNull(),
                        "a read pending across a LOCAL idle timeout (no peer FIN) returned ${outcome.getOrNull()} — " +
                            "the typed close reason was flattened into a clean peer end-of-stream",
                    )
                assertEquals(QuicError.IdleTimeout, thrown.quicError)
            } finally {
                driver.commands.close()
            }
        }

    /** A read issued after the idle close (the ClosedSendChannelException edge). */
    @Test
    fun aReadStartedAfterTheIdleCloseThrowsTheTypedClose() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            api.closeOnTimeout = true
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                runCurrent()
                val slot = openStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                testScheduler.advanceTimeBy(2.seconds)
                runCurrent()
                assertIs<QuicConnectionState.Closed>(driver.state.value)

                val outcome = runCatching { adapter.streamRead(slot.id, bufferFactory, 1024, 30.seconds) }
                val thrown =
                    assertIs<QuicCloseException>(
                        outcome.exceptionOrNull(),
                        "a read after a LOCAL idle close returned ${outcome.getOrNull()} instead of the typed close",
                    )
                assertEquals(QuicError.IdleTimeout, thrown.quicError)
            } finally {
                driver.commands.close()
            }
        }

    /**
     * Bytes quiche still held at the idle close are drained into the slot and delivered first; only the
     * read after them reports the close — typed, because no FIN came with them.
     */
    @Test
    fun bytesDrainedAtTheIdleCloseAreDeliveredBeforeTheTypedClose() =
        runTest {
            val api = StubQuicheApi()
            api.established = true
            api.connTimeout = 1.seconds
            api.closeOnTimeout = true
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                runCurrent()
                val slot = openStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                api.readableStreams.addLast(slot.id.id)
                api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 5, fin = false))
                testScheduler.advanceTimeBy(2.seconds)
                runCurrent()
                assertIs<QuicConnectionState.Closed>(driver.state.value)

                val data =
                    assertIs<ReadResult.Data>(
                        adapter.streamRead(slot.id, bufferFactory, 1024, 30.seconds),
                        "bytes drained at teardown must outrank the close",
                    )
                assertEquals(5, data.buffer.remaining())
                data.buffer.freeIfNeeded()

                val thrown =
                    assertFailsWith<QuicCloseException> {
                        adapter.streamRead(slot.id, bufferFactory, 1024, 30.seconds)
                    }
                assertEquals(QuicError.IdleTimeout, thrown.quicError)
            } finally {
                driver.commands.close()
            }
        }

    private suspend fun openStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return deferred.await()
    }

    private fun createTestDriver(api: StubQuicheApi): QuicheDriver =
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
            clock = RealDriverClock,
            driverContext = EmptyCoroutineContext,
        )
}
