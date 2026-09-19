@file:OptIn(ExperimentalCoroutinesApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.sim.SimClock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A driver whose loop has been cancelled must fail every command it still dequeues — never execute
 * one (#588, the same defect #578 saw from the Android emulator).
 *
 * The captured stack: `run()` on a `StandaloneCoroutine{Cancelled}` reached `execute()` for a queued
 * `RecvPacket` and dereferenced its receive-pool buffer after that buffer had been returned to the
 * pool. A dequeue that does not suspend never observes cancellation, so a loop cancelled while it was
 * busy went on executing whatever was already queued — against quiche, and against memory whose
 * ownership the cancellation had already settled elsewhere.
 *
 * Two interleavings, both exact under the test scheduler:
 *  - [aDriverCancelledMidCommandFailsTheNextQueuedRecvPacketInsteadOfExecutingIt] is the capture: the
 *    loop is cancelled while executing one packet, and the next one queued names a buffer that is
 *    already back in the pool. The pool's own guard is the witness that it was dereferenced.
 *  - [aPacketHandedOverAsTheDriverWasCancelledIsFailedOnceNotLost] is the other half of the same
 *    guarantee: a packet the reader loop handed over in the instant the loop was cancelled must be
 *    failed (released exactly once), not dropped by the cancelled resumption and leaked.
 */
class CancelledDriverQueuedRecvTests {
    private val bufferFactory = BufferFactory.deterministic()

    private fun newDriver(
        stub: StubQuicheApi,
        udp: UdpChannel,
        ingress: DatagramIngress,
        factory: BufferFactory = bufferFactory,
        clock: SimClock,
    ): QuicheDriver =
        QuicheDriver(
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = stub,
            conn = QuicheConn(1L),
            bufferFactory = factory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udp,
            role = QuicRole.Client,
            ingress = ingress,
            clock = clock,
            driverContext = EmptyCoroutineContext,
        )

    /**
     * Delivers one datagram, then — on the reader's very next receive, i.e. after the reader has handed
     * the packet to the driver but before the driver's resumed task has run — cancels the driver and
     * parks. That is the instant the capture's cancellation lands in.
     */
    private class HandoverThenCancelChannel(
        private val cancelDriver: () -> Unit,
    ) : UdpChannel {
        private var receives = 0

        override suspend fun receive(buffer: PlatformBuffer): Int {
            receives++
            if (receives > 1) {
                cancelDriver()
                awaitCancellation()
            }
            repeat(PACKET_LEN) { buffer.writeByte(1) }
            return PACKET_LEN
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome = sendOutcomeOf { }

        override fun close() = Unit
    }

    @Test
    fun aDriverCancelledMidCommandFailsTheNextQueuedRecvPacketInsteadOfExecutingIt() =
        runTest {
            val stub = StubQuicheApi()
            val driver = newDriver(stub, StubUdpChannel(), DatagramIngress.ExternalPump, clock = SimClock(testScheduler))
            val escaped = mutableListOf<Throwable>()
            val simScope = CoroutineScope(coroutineContext + Job() + CoroutineExceptionHandler { _, t -> escaped += t })
            driver.start(simScope)
            runCurrent() // parked in the command dequeue

            val pool = driver.recvBufPool
            val live = pool.allocate(PACKET_LEN)
            val recycled = pool.allocate(PACKET_LEN)
            // The second owner's release: the buffer is back in the pool while a command still names it.
            recycled.freeNativeMemory()

            var fed = 0
            var released = 0
            stub.onConnRecv = {
                fed++
                // Cancelled while busy, with the next packet already queued behind this one — the
                // captured shape. Nothing here suspends, so the loop's next dequeue cannot observe it.
                simScope.cancel()
                driver.commands.trySend(
                    QuicheCmd.RecvPacket(recycled, PACKET_LEN, PacketSource.FromServerSocket(QuicheRecvInfo(1L)) { released++ }),
                )
            }
            driver.commands.trySend(QuicheCmd.RecvPacket(live, PACKET_LEN, PacketSource.FromServerSocket(QuicheRecvInfo(1L)) {}))
            advanceUntilIdle()
            driver.destroy()

            assertEquals(
                emptyList(),
                escaped.map { "${it::class.simpleName}: ${it.message}" },
                "the cancelled loop dereferenced a queued packet's buffer instead of failing the command",
            )
            assertEquals(1, fed, "a cancelled driver fed quiche a packet dequeued after its cancellation")
            assertEquals(1, released, "the packet queued behind the cancellation was not failed exactly once")
        }

    @Test
    fun aPacketHandedOverAsTheDriverWasCancelledIsFailedOnceNotLost() =
        runTest {
            val tracking = TrackingBufferFactory()
            val stub = StubQuicheApi()
            lateinit var simScope: CoroutineScope
            val udp = HandoverThenCancelChannel { simScope.cancel() }
            val driver = newDriver(stub, udp, DatagramIngress.DriverReaderLoop, factory = tracking, clock = SimClock(testScheduler))
            simScope = CoroutineScope(coroutineContext + Job())
            driver.start(simScope)
            var fed = 0
            stub.onConnRecv = { fed++ }

            // The reader hands the packet over (the driver's resumption is now queued), then the
            // cancellation lands, then the driver's queued task runs — already cancelled.
            advanceUntilIdle()
            driver.destroy()
            advanceUntilIdle()

            assertEquals(0, fed, "a cancelled driver executed the packet it was handed instead of failing it")
            // Failed means released: the packet's pool buffer went back to the pool, and the pool's
            // leaf was freed by cleanup. A packet the cancelled resumption dropped is neither.
            tracking.assertNoLeaks()
        }

    private companion object {
        const val PACKET_LEN = 64
    }
}
