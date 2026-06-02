package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the reactive QuicheDriver mechanisms:
 * - StreamSlot signal-based reads
 * - Command loop lifecycle (startup, shutdown, cleanup)
 * - Deferred drain on cleanup (no orphaned awaits)
 * - Error handling (ClosedSendChannel, ClosedReceiveChannel)
 *
 * Runs on all platforms — uses [StubQuicheApi] and [StubUdpChannel].
 */
class ReactiveDriverTests {
    private val bufferFactory = BufferFactory.deterministic()

    // ---- StreamSlot signal tests ----

    @Test
    fun streamSlot_signal_wakes_receiver() =
        runQuicTest {
            val slot = StreamSlot(QuicStreamId(0))
            val received = CompletableDeferred<Boolean>()

            launch {
                slot.dataSignal.receive()
                received.complete(true)
            }
            yield()

            slot.dataSignal.trySend(Unit)
            assertTrue(withTimeout(2.seconds) { received.await() })
        }

    @Test
    fun streamSlot_conflated_coalesces_signals() =
        runQuicTest {
            val slot = StreamSlot(QuicStreamId(0))

            slot.dataSignal.trySend(Unit)
            slot.dataSignal.trySend(Unit)
            slot.dataSignal.trySend(Unit)

            slot.dataSignal.receive()

            val result = withTimeoutOrNull(100) { slot.dataSignal.receive() }
            assertNull(result, "Should not receive after conflated drain")
        }

    @Test
    fun streamSlot_close_unblocks_waiting_receiver() =
        runQuicTest {
            val slot = StreamSlot(QuicStreamId(0))
            val gotException = CompletableDeferred<Boolean>()

            launch {
                try {
                    slot.dataSignal.receive()
                    gotException.complete(false)
                } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                    gotException.complete(true)
                }
            }
            yield()

            slot.dataSignal.close()
            assertTrue(withTimeout(2.seconds) { gotException.await() })
        }

    // ---- Driver command loop tests ----

    @Test
    fun openStream_assigns_sequential_client_ids() =
        runQuicTest {
            val driver = createTestDriver()
            driver.start(this)

            try {
                val s0 = sendOpenStream(driver)
                val s1 = sendOpenStream(driver)
                val s2 = sendOpenStream(driver)

                assertEquals(0L, s0.id.id, "First client stream should be 0")
                assertEquals(4L, s1.id.id, "Second client stream should be 4")
                assertEquals(8L, s2.id.id, "Third client stream should be 8")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun openStream_assigns_sequential_server_ids() =
        runQuicTest {
            val driver = createTestDriver(isServer = true)
            driver.start(this)

            try {
                val s0 = sendOpenStream(driver)
                val s1 = sendOpenStream(driver)

                assertEquals(1L, s0.id.id, "First server stream should be 1")
                assertEquals(5L, s1.id.id, "Second server stream should be 5")
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun state_transitions_to_established() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                val slot = sendOpenStream(driver)
                assertNotNull(slot)
                assertIs<QuicConnectionState.Established>(driver.state.value)
            } finally {
                driver.destroy()
            }
            Unit
        }

    @Test
    fun streamRecv_returns_done_immediately() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                val buf = bufferFactory.allocate(1024)
                val addr = buf.nativeMemoryAccess!!.nativeAddress.toLong()

                val deferred = CompletableDeferred<StreamRecvResult>()
                driver.commands.send(QuicheCmd.StreamRecv(0L, addr, 1024, deferred))
                val result = withTimeout(2.seconds) { deferred.await() }

                assertIs<StreamRecvResult.Done>(result)
                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun streamRecv_returns_data() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Data(42, false)
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                val buf = bufferFactory.allocate(1024)
                val addr = buf.nativeMemoryAccess!!.nativeAddress.toLong()

                val deferred = CompletableDeferred<StreamRecvResult>()
                driver.commands.send(QuicheCmd.StreamRecv(0L, addr, 1024, deferred))
                val result = withTimeout(2.seconds) { deferred.await() }

                assertIs<StreamRecvResult.Data>(result)
                assertEquals(42, result.bytesRead)
                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    // ---- Shutdown & cleanup tests ----

    @Test
    fun destroy_doesNotHang() =
        runQuicTest {
            val driver = createTestDriver()
            driver.start(this)

            val slot = sendOpenStream(driver)
            assertNotNull(slot)

            withTimeout(2.seconds) { driver.destroy() }
        }

    @Test
    fun commands_after_destroy_throw() =
        runQuicTest {
            val driver = createTestDriver()
            driver.start(this)
            driver.destroy()

            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> {
                driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
            }
            Unit
        }

    @Test
    fun connection_close_sets_closed_state() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                sendOpenStream(driver)
                assertIs<QuicConnectionState.Established>(driver.state.value)

                api.closed = true

                val d2 = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, d2))
                d2.await()

                assertIs<QuicConnectionState.Closed>(driver.state.value)
            } finally {
                driver.destroy()
            }
            Unit
        }

    /**
     * **Deterministic** isolation / regression guard for the Close→state ordering race that flaked
     * [connection_close_sets_closed_state] on linuxX64 (observed in a release deploy:
     * "Expected ... Closed, actual ... Established").
     *
     * Invariant: the instant the `Close` command's result deferred completes, the driver has
     * already synced connection state — so a caller awaiting `close()` observes the resulting state
     * with no scheduling-dependent gap. The root fix ([QuicheDriver.execute] calls `updateState()`
     * before completing the Close deferred) establishes this happens-before *on the driver
     * coroutine*.
     *
     * Rather than race the scheduler (probabilistic), this **pins** the driver: at close time the
     * stub emits exactly one datagram (the first [connSend] after [connClose], via
     * [StubQuicheApi.emitOneDatagramOnClose]) and the UDP channel's `send` suspends on a gate. That
     * send is the *only* one in this test, so `afterCommand()` is parked inside `flushOutgoing` —
     * *after* `execute()` completed the Close deferred, but *before* `afterCommand()`'s own
     * `updateState()` can run. When `done.await()` returns, `updateState()` provably has not run
     * yet. With the fix, state was already synced inside `execute()` → `Closed`. Without it, this
     * reads `Established` every time — no timing luck either way. (Verified: fails deterministically
     * when the `updateState()` call in the Close branch is removed.)
     */
    @Test
    fun close_completes_only_after_state_is_synced() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            api.emitOneDatagramOnClose = true
            val udpGate = CompletableDeferred<Unit>()
            val gatedUdp =
                object : UdpChannel {
                    override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

                    // The only send in this test is the single close-time datagram, so pin
                    // unconditionally — this parks the driver in afterCommand() before updateState().
                    override suspend fun send(
                        buffer: PlatformBuffer,
                        len: Int,
                        dest: PathKey?,
                    ) = udpGate.await()

                    override fun close() {}
                }
            val driver = createTestDriver(api, udpChannel = gatedUdp)
            driver.start(this)
            try {
                // Barrier: let the startup afterCommand finish (state → Established, its flush
                // already done) before arming `closed`, so the startup path can't see closed early.
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Established } }
                api.closed = true
                val done = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, done))
                withTimeout(2.seconds) { done.await() }
                assertIs<QuicConnectionState.Closed>(
                    driver.state.value,
                    "Close completed before the connection state was synced to Closed",
                )
            } finally {
                udpGate.complete(Unit) // release the pinned driver so it can finish + clean up
                driver.destroy()
            }
        }

    // ---- UDP send-error handling (regression: shutdown-leak flake) ----
    //
    // QuicheDriver.flushOutgoing() used to let any exception from udpChannel.send()
    // escape run(), which is launched in scope.launch(Dispatchers.Default). The
    // uncaught exception then leaked into the surrounding runTest scope and flaked
    // an unrelated test in the next run. Real-world triggers were
    // PortUnreachableException (peer gone) and ClosedChannelException (channel
    // closed during shutdown). The driver must swallow these, transition to
    // Closed, and unwind cleanly via cleanup().

    @Test
    fun flushOutgoing_swallowsExceptionFromUdpSend() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connSendOnce = 1300 // force one flushOutgoing iteration
            val udp =
                StubUdpChannel(
                    sendBehavior = { _, _ -> throw RuntimeException("simulated PortUnreachable") },
                )
            val driver = createTestDriver(api = api, udpChannel = udp)
            driver.start(this) // would crash run() before the fix; uncaught exception fails runTest

            // Driver must wind down cleanly within the timeout.
            withTimeout(2.seconds) { driver.destroy() }
            assertEquals(1, udp.sendCount, "send was attempted exactly once")
        }

    @Test
    fun flushOutgoing_transitionsToClosedOnUdpError() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connSendOnce = 1300
            val udp = StubUdpChannel(sendBehavior = { _, _ -> throw RuntimeException("send failed") })
            val driver = createTestDriver(api = api, udpChannel = udp)
            driver.start(this)

            // The driver should observe the failure and short-circuit to Closed,
            // closing the command channel so further sends fail predictably.
            withTimeout(2.seconds) {
                while (driver.state.value !is QuicConnectionState.Closed) {
                    yield()
                }
            }
            assertIs<QuicConnectionState.Closed>(driver.state.value)
            assertTrue(driver.commands.isClosedForSend, "commands channel closed after UDP failure")

            driver.destroy()
        }

    @Test
    fun flushOutgoing_failsPendingCommandsAfterUdpError() =
        runQuicTest {
            val api = StubQuicheApi()
            api.connSendOnce = 1300
            val udp = StubUdpChannel(sendBehavior = { _, _ -> throw RuntimeException("send failed") })
            val driver = createTestDriver(api = api, udpChannel = udp)
            driver.start(this)

            // Wait for the driver to short-circuit, then verify a new OpenStream gets
            // ClosedSendChannelException rather than hanging forever.
            withTimeout(2.seconds) {
                while (!driver.commands.isClosedForSend) yield()
            }
            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> {
                driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
            }

            driver.destroy()
            Unit
        }

    // ---- streamWrite back-pressure (QUICHE_ERR_DONE) ----

    /**
     * Regression for the write-path back-pressure fix (#87 suite 3): quiche returns `QUICHE_ERR_DONE`
     * (-1) from `conn_stream_send` when the stream is flow-control blocked with no capacity. That is
     * back-pressure, not failure — [DriverStreamAdapter.streamWrite] must report 0 bytes accepted so the
     * caller retries, NOT throw. It previously threw `SocketClosedException` on any `written < 0`, which
     * made a write larger than the current window fail. A real (more-negative) error still throws.
     */
    @Test
    fun streamWrite_mapsQuicheErrDoneToZeroBackpressure() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
                val buf = bufferFactory.allocate(64)

                // QUICHE_ERR_DONE (-1) -> 0 bytes accepted (back-pressure), no throw.
                api.connStreamSendResult = -1
                assertEquals(0, adapter.streamWrite(QuicStreamId(0L), buf, 2.seconds), "Done must map to 0, not throw")

                // A normal (partial or full) accept passes through unchanged.
                api.connStreamSendResult = 64
                assertEquals(64, adapter.streamWrite(QuicStreamId(0L), buf, 2.seconds))

                // A real negative error code still throws.
                api.connStreamSendResult = -7
                assertFailsWith<SocketClosedException>("a real error must still throw") {
                    adapter.streamWrite(QuicStreamId(0L), buf, 2.seconds)
                }

                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    // ---- streamRead FIN coalesced with data (#91) ----

    /**
     * Regression for #91: when the final stream chunk delivers data **and** the FIN together
     * (`stream_recv` → bytes > 0 && fin), [DriverStreamAdapter.streamRead] returns that Data — and must
     * carry the FIN forward so the *next* read returns [ReadResult.End]. It previously dropped the FIN
     * and parked on the stream's `dataSignal` forever (quiche had already delivered the FIN, so no
     * further data or readable-signal was coming) — an intermittent hang of one of N bulk streams,
     * deterministic here via [StubQuicheApi.streamRecvSequence].
     */
    @Test
    fun streamRead_finCoalescedWithData_yieldsEndOnNextRead() =
        runQuicTest {
            val api = StubQuicheApi()
            // Last chunk: 10 bytes + FIN together; the finished stream then reports Done.
            api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 10, fin = true))
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))

                val first = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.Data>(first, "first read should deliver the coalesced data chunk")
                first.buffer.freeIfNeeded()

                // With the FIN dropped this would park on dataSignal and the streamRead withTimeout would
                // throw; the fix must return End instead.
                val second = adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                assertIs<ReadResult.End>(second, "FIN coalesced with data must yield End on the next read, not hang")
            } finally {
                driver.destroy()
            }
        }

    // ---- stream-command buffer lifetime under cancellation (native heap-corruption regression) ----
    //
    // An address-bearing StreamRecv / StreamSend carries a buffer's *raw native address* into the driver's
    // UNLIMITED command channel. The enqueue (`commands.send`) never suspends, so by the time a read/write's
    // timeout or an external cancel can unwind the caller, the command is already queued — and the driver
    // will later dereference that address (StreamRecv WRITES received bytes into it; StreamSend READS from
    // it). If the caller is allowed to unwind and release the buffer first (free a deterministic buffer, or
    // simply drop the last reference to a GC-backed one so its Cleaner reclaims the native memory), quiche
    // then touches freed memory. For the read path that is a write-after-free → glibc free-list corruption →
    // the rare "SIGSEGV in malloc" crash that failed the JDK17/JNI deploy step.
    //
    // The fix: [DriverStreamAdapter.streamRead]/[streamWrite] wait — non-cancellably — for any in-flight
    // command to complete before unwinding, so the buffer is provably no longer referenced by quiche when it
    // is released. These pin the driver at its startup flush (one gated UDP datagram) so the enqueued command
    // is *guaranteed* unprocessed when the timeout fires, then assert the call does NOT unwind until the
    // driver is released and finishes the command — deterministic, no scheduler races.
    //
    // Negative check: delete the `inFlight?.let { withContext(NonCancellable) { it.join() } }` guard and both
    // `assertNull` checks fail — the call unwinds at its ~150 ms timeout, well inside the 600 ms window.

    private fun gatedStartupDriver(
        api: StubQuicheApi,
        udpGate: CompletableDeferred<Unit>,
    ): QuicheDriver {
        // connSendOnce makes the startup afterCommand() emit exactly one datagram; the UDP channel's send
        // parks on the gate, so the driver is stuck in its initial flush — *before* its loop can dequeue any
        // stream command — until the test releases the gate.
        api.connSendOnce = 1300
        val gatedUdp =
            object : UdpChannel {
                override suspend fun receive(buffer: PlatformBuffer): Int = awaitCancellation()

                override suspend fun send(
                    buffer: PlatformBuffer,
                    len: Int,
                    dest: PathKey?,
                ) = udpGate.await()

                override fun close() {}
            }
        return createTestDriver(api, udpChannel = gatedUdp)
    }

    @Test
    fun streamRead_cancelledWithInflightRecv_waitsForDriverBeforeReleasingBuffer() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Done
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            driver.start(this)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            try {
                // streamRead enqueues a StreamRecv the gated driver cannot process yet, then its 150 ms
                // timeout fires while that command is still queued.
                val read = async { runCatching { adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 150.milliseconds) } }
                // With the fix, `read` is parked in the in-flight join (driver still gated) — it must not
                // unwind within a window well past its own timeout.
                assertNull(
                    withTimeoutOrNull(600) { read.await() },
                    "streamRead unwound while its StreamRecv was still in-flight — the driver could write into the released buffer",
                )
                // Release the driver: it dequeues the StreamRecv, completes the deferred, and the join wakes.
                udpGate.complete(Unit)
                withTimeout(2.seconds) { read.await() }
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                driver.destroy()
            }
        }

    @Test
    fun streamWrite_cancelledWithInflightSend_waitsForDriverBeforeReturning() =
        runQuicTest {
            val api = StubQuicheApi()
            val udpGate = CompletableDeferred<Unit>()
            val driver = gatedStartupDriver(api, udpGate)
            driver.start(this)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            val buf = bufferFactory.allocate(64)
            try {
                val write = async { runCatching { adapter.streamWrite(QuicStreamId(0L), buf, 150.milliseconds) } }
                assertNull(
                    withTimeoutOrNull(600) { write.await() },
                    "streamWrite unwound while its StreamSend was still in-flight — the driver could read the released buffer",
                )
                udpGate.complete(Unit)
                withTimeout(2.seconds) { write.await() }
            } finally {
                if (!udpGate.isCompleted) udpGate.complete(Unit)
                buf.freeNativeMemory()
                driver.destroy()
            }
        }

    // ---- Helpers ----

    private suspend fun sendOpenStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return withTimeout(2.seconds) { deferred.await() }
    }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        isServer: Boolean = false,
        udpChannel: UdpChannel = StubUdpChannel(),
    ): QuicheDriver =
        QuicheDriver(
            api = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udpChannel,
            clientMode = false,
            isServer = isServer,
        )
}
