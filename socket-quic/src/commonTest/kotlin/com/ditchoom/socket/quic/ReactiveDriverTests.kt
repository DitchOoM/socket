package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.nativeMemoryAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
    fun state_transitions_to_established(): Unit =
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
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
        runBlocking(Dispatchers.Default) {
            val driver = createTestDriver()
            driver.start(this)

            val slot = sendOpenStream(driver)
            assertNotNull(slot)

            withTimeout(2.seconds) { driver.destroy() }
        }

    @Test
    fun commands_after_destroy_throw(): Unit =
        runBlocking(Dispatchers.Default) {
            val driver = createTestDriver()
            driver.start(this)
            driver.destroy()

            assertFailsWith<kotlinx.coroutines.channels.ClosedSendChannelException> {
                driver.commands.send(QuicheCmd.OpenStream(CompletableDeferred()))
            }
            Unit
        }

    @Test
    fun connection_close_sets_closed_state(): Unit =
        runBlocking(Dispatchers.Default) {
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

    // ---- Helpers ----

    private suspend fun sendOpenStream(driver: QuicheDriver): StreamSlot {
        val deferred = CompletableDeferred<StreamSlot>()
        driver.commands.send(QuicheCmd.OpenStream(deferred))
        return withTimeout(2.seconds) { deferred.await() }
    }

    private fun createTestDriver(
        api: StubQuicheApi = StubQuicheApi(),
        isServer: Boolean = false,
    ): QuicheDriver =
        QuicheDriver(
            api = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = StubUdpChannel(),
            clientMode = false,
            isServer = isServer,
        )
}
