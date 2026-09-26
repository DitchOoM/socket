@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The narrowest window [runUntilClosed] has to respect: the driver has handed a read its bytes, and the
 * reading coroutine has not yet resumed to return them, when the connection closes.
 *
 * The reader runs on a [ParkingDispatcher] that holds its resumptions until the test releases them, and
 * everything else — the driver loop, the runner's watcher — on the virtual-time scheduler. So the order is
 * forced, not raced: hand-off, then close, then (only when the test says) the reader's resumption.
 */
class ReadHandOffAtCloseTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** Holds every task dispatched to it until [release] runs them, on the caller's thread. */
    private class ParkingDispatcher : CoroutineDispatcher() {
        private val parked = ArrayDeque<Runnable>()

        val hasParked: Boolean get() = parked.isNotEmpty()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            parked.addLast(block)
        }

        /** Runs what is parked now; whatever those tasks dispatch here stays parked for the next call. */
        fun release() {
            repeat(parked.size) { parked.removeFirst().run() }
        }
    }

    /**
     * Let every coroutine run to quiescence, the reader's included — bounded, so a reader that keeps
     * re-dispatching fails the test instead of hanging it, and so no coroutine is left parked for
     * runTest to wait on forever.
     */
    private fun TestScope.settle(reader: ParkingDispatcher) {
        repeat(MAX_SETTLE_ROUNDS) {
            runCurrent()
            if (!reader.hasParked) return
            reader.release()
        }
        fail("the reader was still re-dispatching after $MAX_SETTLE_ROUNDS rounds")
    }

    @Test
    fun aBlockIsNotCancelledWhileAReadHoldsBytesItWasHandedAndTheBytesArrive() =
        runTest(timeout = 30.seconds) {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = HANDED_OFF_BYTES, fin = false))
            val driver = createTestDriver(api)
            driver.start(this)
            val connJob = SupervisorJob()
            val connection =
                DriverQuicConnection(driver, bufferFactory, SocketAddress.ofLiteral("127.0.0.1", 4433), CoroutineScope(connJob))
            val reader = ParkingDispatcher()
            val received = CompletableDeferred<ScopedRead<Int>>()
            val blockEnded = CompletableDeferred<Throwable>()
            val outcome = CompletableDeferred<Throwable?>()
            launch {
                outcome.complete(
                    runCatching {
                        connection.runUntilClosed(linger = 1.hours) {
                            try {
                                val stream = openStream()
                                withContext(reader) { received.complete(stream.read(1.hours) { it.remaining() }) }
                                awaitCancellation()
                            } catch (e: Throwable) {
                                blockEnded.complete(e)
                                throw e
                            }
                        }
                    }.exceptionOrNull(),
                )
            }
            try {
                runCurrent() // the block opens its stream and enters the reader's dispatcher
                reader.release() // the read enqueues its StreamRecv and awaits it
                runCurrent() // the driver answers with the bytes: the hand-off
                assertTrue(api.streamRecvSequence.isEmpty(), "the driver never answered the read, so nothing was handed off")
                assertTrue(reader.hasParked, "the reader must be parked between the hand-off and its resumption")

                // The connection dies with the reader still parked.
                api.closed = true
                driver.commands.trySend(QuicheCmd.Stats(CompletableDeferred()))
                runCurrent()
                assertIs<QuicConnectionState.Closed>(driver.state.value)
                assertEquals(
                    QuicUnreadAtClose.Unread(setOf(QuicStreamId(0L))),
                    connection.unreadAtClose.value,
                    "bytes handed to a read that has not returned them are not read",
                )
                assertFalse(
                    blockEnded.isCompleted,
                    "the block was cancelled while its read held bytes the driver had handed it",
                )

                settle(reader) // the read returns its bytes to the block
                assertEquals(ScopedRead.Data(HANDED_OFF_BYTES), received.getCompleted(), "the handed-off bytes must arrive")
                assertEquals(QuicUnreadAtClose.AllRead, connection.unreadAtClose.value)
                assertIs<CancellationException>(blockEnded.getCompleted(), "once everything is read, the block is cancelled")
                assertIs<QuicCloseException>(outcome.getCompleted())
            } finally {
                connJob.cancel()
                driver.commands.close()
                // Never fails: a failure already on its way out must not be replaced by this one.
                repeat(MAX_SETTLE_ROUNDS) {
                    runCurrent()
                    reader.release()
                }
            }
        }

    /**
     * A read the linger cancels after it was handed bytes returns them to the pool instead of stranding
     * them in a queue no one will drain.
     */
    @Test
    fun aReadCancelledByTheLingerAfterItsHandOffFreesWhatItWasHanded() =
        runTest(timeout = 30.seconds) {
            val api = StubQuicheApi()
            api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = HANDED_OFF_BYTES, fin = false))
            val driver = createTestDriver(api)
            driver.start(this)
            val connJob = SupervisorJob()
            val connection =
                DriverQuicConnection(driver, bufferFactory, SocketAddress.ofLiteral("127.0.0.1", 4433), CoroutineScope(connJob))
            val reader = ParkingDispatcher()
            val outcome = CompletableDeferred<Throwable?>()
            launch {
                outcome.complete(
                    runCatching {
                        connection.runUntilClosed(linger = LINGER) {
                            val stream = openStream()
                            withContext(reader) { stream.read(1.hours) { it.remaining() } }
                            awaitCancellation()
                        }
                    }.exceptionOrNull(),
                )
            }
            try {
                runCurrent()
                reader.release()
                runCurrent()
                assertTrue(reader.hasParked, "the reader must be parked between the hand-off and its resumption")
                api.closed = true
                driver.commands.trySend(QuicheCmd.Stats(CompletableDeferred()))
                runCurrent()
                assertIs<QuicUnreadAtClose.Unread>(connection.unreadAtClose.value)

                testScheduler.advanceTimeBy(LINGER + 1.seconds) // the linger expires with the reader still parked
                runCurrent()
                settle(reader) // the cancelled read resumes, and must give back what it was handed

                assertIs<QuicCloseException>(outcome.getCompleted())
                val pool = driver.streamReadPool.stats()
                assertEquals(
                    pool.totalAllocations.toInt(),
                    pool.currentPoolSize,
                    "every stream read buffer must be back in the pool; stranded: $pool",
                )
            } finally {
                connJob.cancel()
                driver.commands.close()
                // Never fails: a failure already on its way out must not be replaced by this one.
                repeat(MAX_SETTLE_ROUNDS) {
                    runCurrent()
                    reader.release()
                }
            }
        }

    private fun createTestDriver(api: StubQuicheApi): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
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

    private companion object {
        const val HANDED_OFF_BYTES = 5
        const val MAX_SETTLE_ROUNDS = 64
        val LINGER = 30.seconds
    }
}
