package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.SocketClosedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * `androidInstrumentedTest` deliberately does **not** `dependsOn(commonTest)`, so a `commonTest` home
 * covered every platform *except* the one that ships this backend to users: Android is the only target
 * that runs quiche over JNI, and it is where issue #393 was found in the field. This directory is
 * `srcDir`'d into both source sets, so the same source runs unchanged on jvm/apple/linux **and** on a
 * real device — the move adds the lane that was missing and takes none away. See DitchOoM/socket#390.
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

    @Test
    fun connection_close_captures_peer_error_as_typed_reason() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            // The peer sent CONNECTION_CLOSE(PROTOCOL_VIOLATION); quiche_conn_peer_error reports it.
            api.peerError = QuicError.ProtocolViolation
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                sendOpenStream(driver)
                assertIs<QuicConnectionState.Established>(driver.state.value)

                api.closed = true
                val d2 = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, d2))
                d2.await()

                val closed = assertIs<QuicConnectionState.Closed>(driver.state.value)
                // The peer's reason flows into Closed.error as an exhaustive QuicError (was always null
                // before) — and closeReasonOr surfaces it instead of the NoError fallback.
                assertEquals(QuicCloseReason.ByPeer(QuicError.ProtocolViolation), closed.reason)
                // ...and closeReasonOr hands the whole reason — side included — to every throw site,
                // instead of collapsing it to the error and losing who closed us (#437).
                assertEquals(QuicCloseReason.ByPeer(QuicError.ProtocolViolation), driver.closeReasonOr(QuicError.NoError))
                assertTrue(!closed.isCleanShutdown)
            } finally {
                driver.destroy()
            }
            Unit
        }

    @Test
    fun connection_close_falls_back_to_local_error_when_no_peer_error() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            // quiche tore the connection down locally (e.g. it rejected the peer's transport params):
            // no peer error, but a local one.
            api.peerError = null
            api.localError = QuicError.TransportParameterError("local")
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                sendOpenStream(driver)
                api.closed = true
                val d2 = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, d2))
                d2.await()

                val closed = assertIs<QuicConnectionState.Closed>(driver.state.value)
                assertEquals(QuicCloseReason.ByLocal(QuicError.TransportParameterError("local")), closed.reason)
            } finally {
                driver.destroy()
            }
            Unit
        }

    @Test
    fun connection_close_reports_idle_timeout_when_no_close_frame() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            // No peer/local CONNECTION_CLOSE (a stalled/idle connection sends none), but quiche reports
            // the idle timeout fired — the close reason must be the typed IdleTimeout, not a clean null.
            api.peerError = null
            api.localError = null
            api.timedOut = true
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                sendOpenStream(driver)
                api.closed = true
                val d2 = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, d2))
                d2.await()

                val closed = assertIs<QuicConnectionState.Closed>(driver.state.value)
                assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), closed.reason)
                assertTrue(!closed.isCleanShutdown)
            } finally {
                driver.destroy()
            }
            Unit
        }

    @Test
    fun scope_cancelled_connection_still_publishes_the_typed_terminal_reason() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            // A connection that died without any CONNECTION_CLOSE — quiche reports only its idle
            // timeout, never connIsClosed — whose scope is then torn down. That is the teardown path
            // (not the connIsClosed transition), and it used to close `commands` while leaving `state`
            // on Established forever: every later caller resolved its reason through closeReasonOr,
            // read the non-Closed state, and got the NoError fallback — the opaque
            // `QuicCloseException: connection closed` that made the API-35 emulator failure in run
            // 31027926910 undiagnosable. The reason must survive the teardown, typed.
            api.timedOut = true
            val driver = createTestDriver(api)
            val connectionScope = CoroutineScope(coroutineContext + Job())
            driver.start(connectionScope)

            sendOpenStream(driver)
            assertIs<QuicConnectionState.Established>(driver.state.value)

            connectionScope.cancel()

            val closed =
                assertIs<QuicConnectionState.Closed>(
                    withTimeout(5.seconds) { driver.state.first { it is QuicConnectionState.Closed } },
                )
            assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), closed.reason)
            assertEquals(QuicCloseReason.ByLocal(QuicError.IdleTimeout), driver.closeReasonOr(QuicError.NoError))
            Unit
        }

    @Test
    fun clean_close_with_no_quic_error_stays_null() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            // A graceful shutdown: quiche reports NO_ERROR — Closed.error must stay null (clean), not a
            // spurious NoError object.
            api.peerError = QuicError.NoError
            val driver = createTestDriver(api)
            driver.start(this)

            try {
                sendOpenStream(driver)
                api.closed = true
                val d2 = CompletableDeferred<Unit>()
                driver.commands.send(QuicheCmd.Close(QuicError.NoError, d2))
                d2.await()

                val closed = assertIs<QuicConnectionState.Closed>(driver.state.value)
                assertEquals(QuicCloseReason.Graceful, closed.reason)
                assertTrue(closed.isCleanShutdown)
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
                    ): SendOutcome {
                        udpGate.await()
                        return SendOutcome.Sent
                    }

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
    // closed during shutdown). The driver must never let one escape.
    //
    // The ORIGINAL fix also transitioned to Closed, and two tests below pinned that. The
    // containment was right; the termination was not, and it has been reversed:
    //
    //   * A send failure is not a connection-termination event. RFC 9000 §10 lists the only three
    //     (idle timeout, immediate close, stateless reset) and a failed local send is not among
    //     them — it is indistinguishable from a packet lost on the wire, which QUIC retransmits.
    //   * Closing here made active connection migration impossible. A handoff happens *because* the
    //     old path died, so the first send afterwards ended the connection before the new path could
    //     be validated. That is why Apple's migration could never have worked even once its
    //     UdpChannelFactory exists.
    //   * The close reported Closed(error = null) — the *clean shutdown* value — so a network
    //     failure was indistinguishable from a peer closing politely.
    //
    // Termination is now quiche's idle timer, which reports the truthful QuicError.IdleTimeout
    // (IdleTimeoutTerminationTests pins that it fires and what it says). Failure is also no longer
    // signalled by throwing at all: UdpChannel.send returns a typed SendOutcome, so flushOutgoing
    // decides per cause in an exhaustive `when` instead of inheriting one blanket policy.

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

    /**
     * Was `flushOutgoing_transitionsToClosedOnUdpError`, which asserted the exact opposite.
     *
     * The connection must **survive** a failed send. Keeping the old assertion would have permanently
     * blocked connection migration (see the section comment above), so the test is inverted rather
     * than deleted — the scenario it covers is still the one that matters, only its verdict changed.
     */
    @Test
    fun flushOutgoing_keepsConnectionAliveOnUdpError() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            api.connSendOnce = 1300
            val udp = StubUdpChannel(sendBehavior = { _, _ -> throw RuntimeException("send failed") })
            val driver = createTestDriver(api = api, udpChannel = udp)
            driver.start(this)

            try {
                // The send must actually have been attempted, or this asserts nothing.
                withTimeout(2.seconds) {
                    while (udp.sendCount == 0) yield()
                }

                // Give the driver every chance to close before concluding that it did not: poll the
                // state for a bounded window rather than sampling once and hoping the race went our way.
                val closed =
                    withTimeoutOrNull(500.milliseconds) {
                        driver.state.first { it is QuicConnectionState.Closed }
                    }
                assertNull(
                    closed,
                    "a failed UDP send terminated the connection ($closed). A lost datagram is what QUIC " +
                        "retransmits; ending the session over one is what makes migration impossible.",
                )
                assertTrue(
                    !driver.commands.isClosedForSend,
                    "the command channel was closed after a send failure — the driver tore itself down",
                )
            } finally {
                driver.destroy()
            }
        }

    /**
     * Was `flushOutgoing_failsPendingCommandsAfterUdpError`.
     *
     * Its intent — a caller must never hang forever waiting on a driver that has given up — is
     * preserved, but the correct answer is that the driver has *not* given up: it still accepts and
     * completes commands, so nothing hangs. The genuine "peer is gone" signal now arrives from the
     * idle timer instead (IdleTimeoutTerminationTests), on a truthful timescale and with a truthful
     * reason.
     */
    @Test
    fun flushOutgoing_stillAcceptsCommandsAfterUdpError() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = true
            api.connSendOnce = 1300
            val udp = StubUdpChannel(sendBehavior = { _, _ -> throw RuntimeException("send failed") })
            val driver = createTestDriver(api = api, udpChannel = udp)
            driver.start(this)

            try {
                withTimeout(2.seconds) {
                    while (udp.sendCount == 0) yield()
                }

                // A command issued after the failure must still complete — not throw
                // ClosedSendChannelException, and not hang to the timeout.
                val slot =
                    withTimeout(2.seconds) {
                        val deferred = CompletableDeferred<StreamSlot>()
                        driver.commands.send(QuicheCmd.OpenStream(deferred))
                        deferred.await()
                    }
                assertNotNull(slot, "driver did not service a command issued after a UDP send failure")
            } finally {
                driver.destroy()
            }
        }

    // ---- streamWrite reactive back-pressure (writable-signal) ----

    /**
     * The write-path mirror of the read-path `dataSignal` tests. quiche returns `QUICHE_ERR_DONE` (-1)
     * from `conn_stream_send` when the stream's flow-control window is full. That is back-pressure, not
     * failure — and rather than surface a spurious 0 for the caller to delay-poll on (the old behaviour),
     * [DriverStreamAdapter.streamWrite] now **parks reactively** on [StreamSlot.writableSignal] until the
     * driver observes the stream become writable again (`signalWritableStreams` drains
     * [QuicheApi.connWritable]), then retries. Deterministic via [StubQuicheApi.connStreamSendResult] +
     * [StubQuicheApi.writableStreams]; negative-check = drop the `signalWritableStreams()` wiring and this
     * hangs to the `withTimeout`.
     */
    @Test
    fun streamWrite_parksOnFullWindow_thenProgressesWhenWritableSignalFires() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver) // registered in the driver's streams map, so it can be signalled
                val adapter = DriverStreamAdapter(driver, slot)
                val buf = bufferFactory.allocate(64)

                // Window full: every StreamSend returns QUICHE_ERR_DONE -> the writer must PARK, not return 0.
                api.connStreamSendResult = -1
                val write = async { adapter.streamWrite(slot.id, buf, 5.seconds) }
                yield()
                assertNull(
                    withTimeoutOrNull(200) { write.await() },
                    "writer must park on a full window, not return 0",
                )

                // Reopen the window AND report the stream writable, then run one afterCommand (any command):
                // the driver drains connWritable -> writableSignal.trySend -> the parked writer wakes & retries.
                api.connStreamSendResult = 64
                api.writableStreams.addLast(slot.id.id)
                sendOpenStream(driver)

                assertEquals(64, withTimeout(2.seconds) { write.await() }, "writer must resume once the window reopens")

                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    /** A real (more-negative) quiche stream error is not back-pressure — it must still throw, not park. */
    @Test
    fun streamWrite_realErrorThrows() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                val buf = bufferFactory.allocate(64)

                api.connStreamSendResult = -7
                assertFailsWith<SocketClosedException>("a real error must still throw") {
                    withTimeout(2.seconds) { adapter.streamWrite(slot.id, buf, 2.seconds) }
                }

                buf.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    /**
     * Peer STOP_SENDING / RESET_STREAM on ONE stream (quiche `STREAM_STOPPED` -15 / `STREAM_RESET` -16,
     * RFC 9000 §19.4-19.5) is a STREAM-level event — the connection is healthy. The write must raise a
     * stream-scoped [QuicStreamException], **not** a connection-close [QuicCloseException] /
     * [SocketClosedException]: conflating the two tears down a good connection when a peer merely cancels
     * one stream (e.g. an HTTP/3 client cancelling a server PUSH). Regression for
     * project_quic_stream_stopped_bug. Negative-check = revert the -15/-16 branch in
     * [DriverStreamAdapter.streamWrite] and this fails with a SocketClosedException.
     */
    @Test
    fun streamWrite_peerStopSendingOrReset_throwsStreamErrorNotConnectionClose() =
        runQuicTest {
            for (code in listOf(QuicheDriver.QUICHE_ERR_STREAM_STOPPED, QuicheDriver.QUICHE_ERR_STREAM_RESET)) {
                val api = StubQuicheApi()
                val driver = createTestDriver(api)
                driver.start(this)
                try {
                    val slot = sendOpenStream(driver)
                    val adapter = DriverStreamAdapter(driver, slot)
                    val buf = bufferFactory.allocate(64)

                    api.connStreamSendResult = code
                    api.connStreamSendErrorCode = QuicAppErrorCode(0x10c) // quiche's out_error_code on STREAM_STOPPED/RESET
                    val ex =
                        assertFailsWith<QuicStreamException>("stream-level quiche error $code must throw a stream error") {
                            withTimeout(2.seconds) { adapter.streamWrite(slot.id, buf, 2.seconds) }
                        }
                    assertEquals(slot.id.id, ex.streamId, "exception must carry the affected stream id")
                    val expectedAbort =
                        if (code == QuicheDriver.QUICHE_ERR_STREAM_STOPPED) {
                            QuicStreamAbort.StopSending(QuicAppErrorCode(0x10c))
                        } else {
                            QuicStreamAbort.ResetStream(QuicAppErrorCode(0x10c))
                        }
                    assertEquals(expectedAbort, ex.abort, "exception must carry the typed abort for quiche code $code")
                    assertEquals(
                        QuicAppErrorCode(0x10c),
                        ex.abort.applicationErrorCode,
                        "the peer application error code from quiche's out_error_code must round-trip",
                    )
                    // Widen to Any so this stays a real runtime guard: Kotlin 2.4.0 promotes a
                    // statically-provable `is` check to a hard error, and QuicStreamException is not a
                    // SocketClosedException subtype. The cast keeps the regression check that the two
                    // hierarchies never merge without tripping the compile-time tautology error.
                    assertTrue(
                        (ex as Any) !is SocketClosedException,
                        "a stopped/reset stream is not a closed connection — must not be a SocketClosedException",
                    )

                    buf.freeNativeMemory()
                } finally {
                    driver.destroy()
                }
            }
        }

    /**
     * The connection survives a peer stopping one stream: after a write hits `STREAM_STOPPED` and throws
     * a stream error, a write on a DIFFERENT stream of the same driver still goes through. This is the
     * behavioural contract the [QuicStreamException]/[QuicCloseException] split exists to protect.
     */
    @Test
    fun streamStopped_connectionStaysUsable_anotherStreamRoundTrips() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val stopped = sendOpenStream(driver)
                val stoppedAdapter = DriverStreamAdapter(driver, stopped)
                val buf1 = bufferFactory.allocate(64)
                api.connStreamSendResult = QuicheDriver.QUICHE_ERR_STREAM_STOPPED
                assertFailsWith<QuicStreamException> {
                    withTimeout(2.seconds) { stoppedAdapter.streamWrite(stopped.id, buf1, 2.seconds) }
                }
                buf1.freeNativeMemory()

                // The connection is unaffected — a fresh stream writes normally.
                val healthy = sendOpenStream(driver)
                val healthyAdapter = DriverStreamAdapter(driver, healthy)
                val buf2 = bufferFactory.allocate(64)
                api.connStreamSendResult = 64
                assertEquals(
                    64,
                    withTimeout(2.seconds) { healthyAdapter.streamWrite(healthy.id, buf2, 2.seconds) },
                    "the connection must stay usable after one stream was stopped",
                )
                buf2.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    /** An empty write is a 0-byte no-op — it must never park, even when the window is full. */
    @Test
    fun streamWrite_emptyBuffer_returnsZeroWithoutParking() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                val empty = bufferFactory.allocate(0)

                api.connStreamSendResult = -1 // "window full" — an empty write must still return 0 immediately
                assertEquals(0, withTimeout(2.seconds) { adapter.streamWrite(slot.id, empty, 2.seconds) })

                empty.freeNativeMemory()
            } finally {
                driver.destroy()
            }
        }

    /** A writer parked on a full window must wake into [SocketClosedException] when the connection closes. */
    @Test
    fun streamWrite_connectionClosedWhileParked_throwsSocketClosed() =
        runQuicTest {
            val api = StubQuicheApi()
            val driver = createTestDriver(api)
            driver.start(this)
            val slot = sendOpenStream(driver)
            val adapter = DriverStreamAdapter(driver, slot)
            val buf = bufferFactory.allocate(64)

            api.connStreamSendResult = -1 // window full -> writer parks on writableSignal
            // runCatching so the eventual SocketClosedException is captured in the result, not propagated
            // to this scope as an uncaught async-child failure (which would fail the test before we assert).
            val write = async { runCatching { adapter.streamWrite(slot.id, buf, 5.seconds) } }
            yield()
            assertNull(withTimeoutOrNull(200) { write.await() }, "writer should be parked")

            driver.destroy() // closes commands -> cleanup() closes writableSignal -> parked writer unblocks

            val result = withTimeout(2.seconds) { write.await() }
            assertIs<SocketClosedException>(
                result.exceptionOrNull(),
                "closing while parked must surface as SocketClosedException",
            )

            buf.freeNativeMemory()
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

    // ---- connection teardown must not discard buffered stream data (#318) ----
    //
    // The half-close shape from AndroidQuicServerTests.halfCloseAllowsReadAfterSendFin: the peer replies,
    // FINs the stream, and closes the connection. Our transport accepted (and acked) those bytes, so the
    // connection dying does not un-receive them — RFC 9000 §10.2 ends the *connection*, and quiche keeps
    // the stream readable while it drains. Before the fix the reader's wakeup raced the teardown: if it
    // lost, `commands` was already closed and `streamRead` reported ReadResult.End over data quiche was
    // still holding, which `quiche_conn_free` then destroyed — `expected:<[ping]> but was:<[no_data:End]>`.
    //
    // Both tests pin the losing interleaving deterministically: one `afterCommand` signals the stream as
    // readable AND observes the connection closed, with no suspension point in between, so the reader
    // cannot get its StreamRecv in first. Negative check (the mutation proof): drop
    // `drainReadableStreamsIntoSlots()` from `transitionToClosed` and both fail with End instead of Data.

    /** A reader parked on `dataSignal` when the connection dies must still be handed the buffered bytes. */
    @Test
    fun streamRead_parkedWhenConnectionCloses_deliversBufferedDataBeforeEnd() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Done // nothing readable yet -> the reader parks
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)

                val read = async { adapter.streamRead(slot.id, bufferFactory, 1024, 5.seconds) }
                assertNull(
                    withTimeoutOrNull(200) { read.await() },
                    "reader should be parked on dataSignal — nothing has been made readable yet",
                )

                // The peer's reply + FIN land in quiche, then its CONNECTION_CLOSE: one afterCommand
                // reports the stream readable (first sweep) and then sees the connection closed, which
                // drains quiche (second sweep) and closes `commands` under the still-parked reader.
                api.readableStreams.addLast(slot.id.id)
                api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 4, fin = true))
                api.closed = true
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))

                val result = withTimeout(2.seconds) { read.await() }
                val data = assertIs<ReadResult.Data>(result, "buffered bytes must outrank the End verdict")
                assertEquals(4, data.buffer.remaining(), "the whole drained chunk must be delivered")
                data.buffer.freeIfNeeded()

                // The FIN the drain carried forward ends the stream on the next read — no park, no hang.
                assertIs<ReadResult.End>(
                    adapter.streamRead(slot.id, bufferFactory, 1024, 2.seconds),
                    "once the drained chunks are gone the recorded FIN must end the stream",
                )
            } finally {
                driver.destroy()
            }
        }

    /** A read issued *after* the connection is already gone must also see the bytes drained on the way out. */
    @Test
    fun streamRead_startedAfterConnectionClosed_deliversBufferedDataBeforeEnd() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)

                api.readableStreams.addLast(slot.id.id)
                api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 7, fin = true))
                api.closed = true
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }

                val result = adapter.streamRead(slot.id, bufferFactory, 1024, 2.seconds)
                val data = assertIs<ReadResult.Data>(result, "a post-teardown read must still drain the slot")
                assertEquals(7, data.buffer.remaining())
                data.buffer.freeIfNeeded()

                assertIs<ReadResult.End>(adapter.streamRead(slot.id, bufferFactory, 1024, 2.seconds))
            } finally {
                driver.destroy()
            }
        }

    /**
     * Closing the stream releases whatever the teardown drain left undelivered — `read()` is rejected
     * after close, so holding those pooled buffers for the slot's lifetime would be a leak.
     */
    @Test
    fun streamClose_releasesUndeliveredTeardownChunks() =
        runQuicTest {
            val api = StubQuicheApi()
            api.streamRecvResult = StreamRecvResult.Done
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                val slot = sendOpenStream(driver)
                val adapter = DriverStreamAdapter(driver, slot)
                val stream = QuicheStreamByteStream(slot.id, adapter, driver.streamReadPool)

                // Two chunks drained at teardown; the reader takes one and abandons the rest by closing.
                api.readableStreams.addLast(slot.id.id)
                api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 11, fin = false))
                api.streamRecvSequence.addLast(StreamRecvResult.Data(bytesRead = 5, fin = true))
                api.closed = true
                driver.commands.send(QuicheCmd.Stats(CompletableDeferred()))
                withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }

                val first = stream.read(2.seconds)
                assertIs<ReadResult.Data>(first).buffer.freeIfNeeded()

                stream.close()
                assertNull(
                    slot.pendingData.tryReceive().getOrNull(),
                    "close() must release every undelivered chunk",
                )
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
                ): SendOutcome {
                    udpGate.await()
                    return SendOutcome.Sent
                }

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

    // ---- Reactive keepalive ----

    @Test
    fun keepAlive_schedulesAckElicitingPings_onIdleConnection() =
        runQuicTest {
            // Tier-1 deterministic timing: a [ManualDriverClock] fires the keepalive timer by hand, so there
            // is no wall-clock dependence at all. Stub defaults: established=true, connTimeout=null — so the
            // keepalive deadline is the only armed timer, and each clock.advance() is exactly one PING.
            val api = StubQuicheApi()
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
            driver.start(this)
            try {
                clock.advance(1.seconds)
                assertEquals(1, api.ackElicitingCount, "first keepalive deadline did not schedule a PING")
                clock.advance(1.seconds)
                assertEquals(2, api.ackElicitingCount, "second keepalive deadline did not schedule a PING")
                assertEquals(0, api.onTimeoutCount, "keepalive deadline must not be handed to quiche as an idle timeout")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun keepAlive_manualClock_isDeterministicUnderRepeatedFires() =
        runQuicTest {
            // Proves ManualDriverClock.advance() is race-free: it must return only AFTER the driver has fully
            // processed each fire. Asserting the EXACT cumulative count after EVERY one of many fires would
            // fail on the first slipped iteration if advance() raced the driver's timer branch.
            val api = StubQuicheApi()
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
            driver.start(this)
            try {
                repeat(200) { i ->
                    clock.advance(1.seconds)
                    assertEquals(i + 1, api.ackElicitingCount, "fire ${i + 1} not observed on return — advance() raced the timer branch")
                }
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun keepAlive_manualClock_stress_manyConcurrentDriversStayDeterministic() =
        runQuicTest {
            // Regression guard for the ManualDriverClock initial-arm race (the FFM/JDK17 CI flake): a single
            // driver rarely loses the advance()-vs-armTimeout-trySend window on a fast box, so fan out many
            // independent driver+clock pairs advancing concurrently on Dispatchers.Default. Each pair's FIRST
            // advance is the racy one, so 64 of them per run multiplies the exposure by ~64×. Every fire must
            // still be observed on return for every driver.
            val drivers = 64
            val firesEach = 40
            coroutineScope {
                repeat(drivers) { d ->
                    launch {
                        val api = StubQuicheApi()
                        val clock = ManualDriverClock()
                        val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
                        driver.start(this)
                        try {
                            repeat(firesEach) { i ->
                                clock.advance(1.seconds)
                                assertEquals(
                                    i + 1,
                                    api.ackElicitingCount,
                                    "driver $d: fire ${i + 1} not observed on return — advance() raced the timer branch",
                                )
                            }
                        } finally {
                            driver.commands.close()
                        }
                    }
                }
            }
        }

    @Test
    fun keepAlive_handsTimerToQuiche_whenQuicheTimeoutIsSooner() =
        runQuicTest {
            // When quiche's own timer (idle/loss recovery) is due before the keepalive deadline, the driver
            // must hand the fire to quiche (connOnTimeout), NOT send a PING. connTimeout 50ms < keepalive 10s.
            val api = StubQuicheApi()
            api.connTimeout = 50.milliseconds
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = 10.seconds, clock = clock)
            driver.start(this)
            try {
                clock.advance(50.milliseconds)
                assertEquals(0, api.ackElicitingCount, "quiche timer was sooner — the driver must not PING")
                assertEquals(1, api.onTimeoutCount, "quiche timer fire was not handed to connOnTimeout")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun keepAlive_disabled_sendsNoPings() =
        runQuicTest {
            // No keepAliveInterval → the driver must NEVER PING, only hand timer fires to quiche. A non-null
            // connTimeout keeps a timer armed so the ManualDriverClock has something to fire and the
            // assertion is "fired the timer, still no PING" — strictly stronger than "no PING within 300ms".
            val api = StubQuicheApi()
            api.connTimeout = 50.milliseconds
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = null, clock = clock)
            driver.start(this)
            try {
                clock.advance(50.milliseconds)
                clock.advance(50.milliseconds)
                assertEquals(0, api.ackElicitingCount, "keepalive disabled but the driver still sent ack-eliciting PINGs")
                assertEquals(2, api.onTimeoutCount, "both timer fires should have been handed to quiche")
            } finally {
                driver.commands.close()
            }
        }

    @Test
    fun keepAlive_notScheduledBeforeHandshakeEstablished() =
        runQuicTest {
            // Keepalive only counts once established (a half-open connection has nothing to keep alive). With
            // established=false the keepalive deadline is suppressed, so a fired timer goes to quiche, not a PING.
            val api = StubQuicheApi()
            api.established = false
            api.connTimeout = 50.milliseconds
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = 1.seconds, clock = clock)
            driver.start(this)
            try {
                clock.advance(1.seconds)
                assertEquals(0, api.ackElicitingCount, "keepalive must not PING before the handshake is established")
                assertEquals(1, api.onTimeoutCount, "pre-established timer fire should be handed to quiche")
            } finally {
                driver.commands.close()
            }
        }

    // ---- Idle timeout ----

    @Test
    fun idleTimeout_quicheClose_transitionsDriverToClosedAndClosesCommands() =
        runQuicTest {
            // The terminal path the wall-clock integration test (QuicIdleTimeoutTestSuite) covers, made
            // deterministic: quiche's idle timer fires, quiche idle-closes, and the driver must transition to
            // Closed AND close its command channel (the coupled signal a parked read keys off to return End).
            // No keepalive (so the fire is handed to quiche); closeOnTimeout makes that fire idle-close.
            val api = StubQuicheApi()
            api.connTimeout = 50.milliseconds
            api.closeOnTimeout = true
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = null, clock = clock)
            driver.start(this)
            // Terminal fire: the driver closes and never re-arms, so synchronise on the Closed state, not a re-arm.
            clock.fireExpectingNoRearm(50.milliseconds)
            withTimeout(2.seconds) { driver.state.first { it is QuicConnectionState.Closed } }
            assertTrue(driver.commands.isClosedForSend, "command channel must close coupled with the Closed state")
            assertEquals(1, api.onTimeoutCount, "the idle timer fire should have been handed to quiche")
        }

    // ---- Establishment gate ----

    @Test
    fun awaitEstablished_handshakeThatIdleTimesOut_failsWithTheTypedReason() =
        runQuicTest {
            // Regression for the linuxX64 :socket-http3 flake in release run 30954202211: the handshake
            // never completed, quiche idle-closed it, and `awaitEstablished` — which only waited for "no
            // longer Handshaking" — returned SUCCESSFULLY on the Closed state. The caller then walked into
            // Http3Connection.bootstrap and hit the already-closed command channel, so a *handshake* idle
            // timeout was reported as an `openUniStream` failure several frames away from the connect call.
            //
            // The gate must fail at establishment instead, carrying the reason quiche actually recorded.
            val api = StubQuicheApi()
            api.established = false // handshake never completes
            api.connTimeout = 50.milliseconds
            api.closeOnTimeout = true
            api.timedOut = true // → resolveCloseError() reports IdleTimeout
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = null, clock = clock)
            driver.start(this)

            // supervisorScope so the gate's failure lands in await() rather than cancelling the test.
            val failure =
                supervisorScope {
                    val gate = async { driver.awaitEstablished(5.seconds) }
                    // Park the gate on the state flow before the idle timer fires, so this pins the ordering
                    // that actually occurred in CI (waiter first, close second), not a pre-closed shortcut.
                    yield()

                    clock.fireExpectingNoRearm(50.milliseconds)

                    assertFailsWith<QuicCloseException> { withTimeout(2.seconds) { gate.await() } }
                }
            assertEquals(
                QuicError.IdleTimeout,
                failure.quicError,
                "the establishment failure must carry quiche's recorded reason, not a generic close",
            )
        }

    /**
     * The other way a handshake can fail to settle: nothing closes it, and the CALLER's bound elapses
     * first (#480). With the production shape — a 15s bound against a 30s idle timeout — this is the
     * timer that actually fires, and it used to surface as the bare `TimeoutCancellationException`
     * from `withTimeout`. That is a `CancellationException`: a `launch` that dies of one is *cancelled*,
     * not failed, so the establishment failure reached no handler (the #472 silent-death mechanism,
     * here on the connect path). The connection was then torn down by scope cancellation and read
     * `Closed(Unspecified)` — the "honest unknown" — for a close whose reason was perfectly known.
     *
     * The bound must instead end the handshake the way every other establishment failure ends it:
     * a typed close the state channel and the thrown channel agree on, and a NO_ERROR frame to quiche
     * rather than the reason's `-1` reinterpreted as `u64`.
     */
    @Test
    fun awaitEstablished_boundElapsesMidHandshake_closesWithHandshakeTimeoutNotTheCallersCancellation() =
        runQuicTest {
            val api = StubQuicheApi()
            api.established = false // handshake never completes
            // quiche's own timer is armed and LONGER than the bound — the manual clock never fires it,
            // which is exactly the production case: the idle timer is not what ends this connection.
            api.connTimeout = 10.seconds
            val clock = ManualDriverClock()
            val driver = createTestDriver(api, keepAliveInterval = null, clock = clock)
            driver.start(this)
            try {
                val bound = 200.milliseconds
                val failure = assertFailsWith<QuicCloseException> { driver.awaitEstablished(bound) }

                val expected = QuicCloseReason.ByLocal(QuicError.HandshakeTimeout(bound))
                assertEquals(
                    expected,
                    failure.closeReason,
                    "the bound must be reported as a typed local close naming the bound, not the caller's cancellation",
                )
                assertEquals(
                    QuicConnectionState.Closed(expected),
                    driver.state.value,
                    "the state channel is the single source of truth for the reason; it must agree with the throw",
                )
                assertTrue(driver.commands.isClosedForSend, "abandoning the handshake must close the command channel")
                assertEquals(
                    listOf<QuicError>(QuicError.NoError),
                    api.closeErrors,
                    "a reason with no transport code has nothing truthful to put on the wire but NO_ERROR",
                )
            } finally {
                driver.destroy()
            }
        }

    @Test
    fun awaitEstablished_returnsOnlyOnEstablished() =
        runQuicTest {
            // The other half of the gate: a genuinely established connection must still pass through
            // (and reach Established, not merely "not Handshaking") — so the fix above cannot be
            // satisfied by a gate that rejects everything.
            val api = StubQuicheApi()
            api.established = true
            val driver = createTestDriver(api)
            driver.start(this)
            try {
                withTimeout(2.seconds) { driver.awaitEstablished(2.seconds) }
                assertIs<QuicConnectionState.Established>(driver.state.value)
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
        udpChannel: UdpChannel = StubUdpChannel(),
        keepAliveInterval: kotlin.time.Duration? = null,
        clock: DriverClock = RealDriverClock,
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
            clientMode = false,
            isServer = isServer,
            keepAliveInterval = keepAliveInterval,
            clock = clock,
        )
}
