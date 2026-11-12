package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.CloseableBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * Lifetime races between what a driver launches and what it frees (confinement audit, F1, F2, F6).
 *
 * Each test sets the interleaving up explicitly rather than looping until it happens.
 */
@OptIn(ExperimentalAtomicApi::class)
class DriverLifetimeRaceTests {
    private val bufferFactory = BufferFactory.deterministic()

    /** A channel whose receive is parked until cancelled, counting the receives that are in flight. */
    private class ParkedReceiveChannel : UdpChannel {
        private val entered = AtomicInt(0)
        private val parked = AtomicInt(0)
        val receivesEntered: Int get() = entered.load()

        /** Receives that have started and not yet ended: a reader of this channel that is still alive. */
        val receivesParked: Int get() = parked.load()

        override suspend fun receive(buffer: PlatformBuffer): Int {
            entered.fetchAndIncrement()
            parked.incrementAndFetch()
            try {
                awaitCancellation()
            } finally {
                parked.decrementAndFetch()
            }
        }

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome = SendOutcome.Sent

        override fun close() = Unit
    }

    /**
     * The loop exits before the starting thread has finished starting the driver.
     *
     * Made explicit with a dispatcher that runs a launched coroutine inline: `launch(run)` returns only
     * after `run()` has ended, because `commands` was closed beforehand. The reader used to be launched
     * after that, by `start()`, into a driver whose `finally` had already looked for it and cleared the
     * pools; nothing was left to cancel it.
     */
    @Test
    fun aLoopThatEndsDuringStartLeavesNoReaderBehind() =
        runQuicTest {
            val channel = ParkedReceiveChannel()
            val driver =
                createDriver(udpChannel = channel, ingress = DatagramIngress.DriverReaderLoop, driverContext = Dispatchers.Unconfined)
            driver.commands.close()
            val scope = CoroutineScope(Job())
            try {
                driver.start(scope)
                assertEquals(
                    0,
                    channel.receivesParked,
                    "a reader is still parked in receive() after the driver's loop ended and its cleanup ran " +
                        "(receives entered: ${channel.receivesEntered})",
                )
            } finally {
                scope.cancel()
            }
        }

    /** Starting a driver twice would run a second loop over the same connection. */
    @Test
    fun aDriverStartsOnce() =
        runQuicTest {
            val driver = createDriver()
            val scope = CoroutineScope(Job())
            try {
                driver.start(scope)
                assertFailsWith<IllegalStateException>("a second start() must be refused") { driver.start(scope) }
            } finally {
                driver.destroy()
                scope.cancel()
            }
        }

    /**
     * A channel whose first send can be abandoned (the stall bound's cancel) and whose retry, the second
     * send, ends only when released, whatever cancellation says: the shape of an io_uring submission that
     * the kernel still owns until its completion arrives. It records whether the datagram it was handed
     * had been freed when the kernel let go of it.
     */
    private class KernelOwnedRetryChannel : UdpChannel {
        private val sends = AtomicInt(0)
        val sendCount: Int get() = sends.load()
        private val release = CompletableDeferred<Unit>()
        private val retried = CompletableDeferred<PlatformBuffer>()

        /** The datagram the retry was handed. */
        suspend fun retriedBuffer(): PlatformBuffer = retried.await()

        fun letGo() = release.complete(Unit)

        override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

        override suspend fun send(
            buffer: PlatformBuffer,
            len: Int,
            target: SendTarget,
        ): SendOutcome {
            if (sends.incrementAndFetch() == 1) awaitCancellation()
            retried.complete(buffer)
            withContext(NonCancellable) { release.await() }
            return SendOutcome.Sent
        }

        override fun close() = Unit
    }

    /** Counts the driver's `connFree`, which comes after the path teardown in `cleanup()`. */
    private class FreeSpy(
        private val delegate: QuicheApi,
        val freed: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : QuicheApi by delegate {
        override fun connFree(conn: QuicheConn) {
            delegate.connFree(conn)
            freed.complete(Unit)
        }
    }

    /**
     * A stalled datagram belongs to its retry until the retry returns. The driver closes while the retry is
     * still inside the platform; the datagram must outlive the teardown and be freed only once the retry has
     * ended, not be freed under it.
     */
    @Test
    fun aStalledDatagramIsFreedAfterItsRetryEndsNotUnderIt() =
        runQuicTest(timeout = 20.seconds) {
            val stub = StubQuicheApi()
            stub.established = true
            stub.connTimeout = 1.seconds
            stub.closeOnTimeout = false
            val spy = FreeSpy(stub)
            val channel = KernelOwnedRetryChannel()
            val clock = ManualDriverClock()
            // A factory whose buffers report whether they were freed: the deterministic one's do not on every platform.
            val driver =
                createDriver(
                    api = spy,
                    udpChannel = channel,
                    clock = clock,
                    driverContext = Dispatchers.Default,
                    bufferFactory = TrackingBufferFactory(),
                )
            val scope = CoroutineScope(Job())
            driver.start(scope)
            try {
                stub.connSendOnce = 1200
                val firing = async { clock.fireExpectingNoRearm(1.seconds) }
                assertNotNull(withTimeoutOrNull(3.seconds) { while (channel.sendCount == 0) yield() }, "the timer never reached a send")
                assertNotNull(withTimeoutOrNull(3.seconds) { clock.stall() }, "the send bound was not fired")
                firing.await()
                val lent = withTimeout(3.seconds) { channel.retriedBuffer() } as CloseableBuffer

                driver.commands.close()
                withTimeout(5.seconds) { spy.freed.await() }

                assertFalse(
                    lent.isFreed,
                    "the stalled datagram was freed while its retry was still inside the platform, which may still read it",
                )
                channel.letGo()
                assertNotNull(
                    withTimeoutOrNull(3.seconds) { while (!lent.isFreed) yield() },
                    "the stalled datagram was never freed after its retry ended",
                )
            } finally {
                channel.letGo()
                scope.cancel()
            }
        }

    private fun createDriver(
        api: QuicheApi = StubQuicheApi(),
        udpChannel: UdpChannel = StubUdpChannel(),
        ingress: DatagramIngress = DatagramIngress.ExternalPump,
        clock: DriverClock = RealDriverClock,
        driverContext: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        bufferFactory: BufferFactory = this.bufferFactory,
    ): QuicheDriver =
        QuicheDriver(
            // Test double: never exercises a path move.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udpChannel,
            role = QuicRole.Client,
            ingress = ingress,
            clock = clock,
            driverContext = driverContext,
        )
}
