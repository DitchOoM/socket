package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.coroutines.CompletableDeferred
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
