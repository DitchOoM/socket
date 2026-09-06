package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **A throwing backend must fail the awaiting caller, never wedge it** (found during the #401 hunt).
 *
 * Stream commands are dequeued before they run, so [QuicheDriver.cleanup]'s teardown drain can
 * never reach one whose backend call threw mid-execution — `failCommand` only sees what is still
 * queued. `streamRead`/`streamWrite` (and the datagram adapter) end with a NonCancellable `join()`
 * on that command's deferred: the barrier that keeps a caller from freeing a buffer whose raw
 * native address the command still carries. An uncompleted deferred turns that barrier into a
 * permanent, uncancellable hang — no timeout can reach it, no teardown completes it, and the test
 * or application thread is gone for good. The PeerCert arm guarded itself against exactly this
 * from the day it landed; these tests pin the same guarantee for the arms that carry caller
 * buffer addresses, now enforced once at the dispatch site (`failCommandExceptionally`).
 *
 * Mutation proof: revert the dispatch-site guard in [QuicheDriver.run] and both tests hang until
 * `runQuicTest`'s cap kills them, instead of completing exceptionally within their own timeouts.
 *
 * ## Why this lives in `src/sharedQuicheTestSuites/kotlin` rather than `commonTest`
 * Same reason as [StreamResetReadTests]: this directory is `srcDir`'d into both the platform test
 * source sets and `androidInstrumentedTest`, so one copy runs everywhere including the Android
 * device lane (DitchOoM/socket#390).
 */
class DriverCommandFailureTests {
    private val bufferFactory = BufferFactory.deterministic()

    private fun createTestDriver(
        api: QuicheApi,
        udpChannel: UdpChannel = StubUdpChannel(),
    ): QuicheDriver =
        QuicheDriver(
            // Test double: these tests never move a path.
            migration = MigrationCapability.BackendCannotMigrate,
            rawApi = api,
            conn = QuicheConn(1L),
            bufferFactory = bufferFactory,
            recvInfo = QuicheRecvInfo(1L),
            sendInfo = QuicheSendInfo(1L),
            udpChannel = udpChannel,
            clientMode = false,
            isServer = false,
        )

    @Test
    fun aThrowingSendBackendFailsTheWriterInsteadOfWedgingIt() =
        runQuicTest {
            val api =
                object : QuicheApi by StubQuicheApi() {
                    override fun connStreamSend(
                        conn: QuicheConn,
                        streamId: QuicStreamId,
                        buf: Long,
                        bufLen: Int,
                        fin: Boolean,
                    ): StreamSendResult = throw IllegalStateException("backend blew up mid-send")
                }
            val driver = createTestDriver(api)
            // A supervisor scope with a swallow-all handler, not the test scope: the backend throw
            // is *designed* to unwind the driver coroutine (run() rethrows after completing the
            // command), and on the test scope that structured-concurrency failure — or, on a bare
            // supervisor, the uncaught-exception report — would fail the test even though the
            // caller saw exactly the exception these tests assert on.
            val driverScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            driver.start(driverScope)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            val buf = bufferFactory.allocate(8)
            buf.writeString("ping", Charset.UTF8)
            buf.resetForRead()
            try {
                assertFailsWith<IllegalStateException>(
                    "a throwing connStreamSend must surface to the writer, not wedge its NonCancellable join",
                ) {
                    // The outer bound exists only to convert a regression back into a fast red test:
                    // pre-guard, the writer's own timeout fires, its finally joins a deferred nobody
                    // will ever complete, and not even this withTimeout can cancel that join.
                    withTimeout(5.seconds) { adapter.streamWrite(QuicStreamId(0L), buf, 2.seconds) }
                }
            } finally {
                buf.freeNativeMemory()
                driver.destroy()
                driverScope.cancel()
            }
        }

    @Test
    fun aThrowingRecvBackendFailsTheReaderInsteadOfWedgingIt() =
        runQuicTest {
            val api =
                object : QuicheApi by StubQuicheApi() {
                    override fun connStreamRecv(
                        conn: QuicheConn,
                        streamId: QuicStreamId,
                        buf: Long,
                        bufLen: Int,
                    ): StreamRecvResult = throw IllegalStateException("backend blew up mid-recv")
                }
            val driver = createTestDriver(api)
            // Supervisor scope + swallow-all handler for the same reason as the send test above.
            val driverScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            driver.start(driverScope)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            try {
                assertFailsWith<IllegalStateException>(
                    "a throwing connStreamRecv must surface to the reader, not wedge its NonCancellable join",
                ) {
                    withTimeout(5.seconds) {
                        adapter.streamRead(QuicStreamId(0L), bufferFactory, 1024, 2.seconds)
                    }
                }
            } finally {
                driver.destroy()
                driverScope.cancel()
            }
        }

    /**
     * **The same wedge, reached without a throw** — the amplifier behind the 2026-09-03 walk.
     *
     * The two tests above pin the guarantee for a backend that *throws*: the dispatch-site guard
     * (`failCommandExceptionally`) completes the deferred exceptionally, so the caller's
     * NonCancellable join has something to join and the writer unwinds. That guard fires only when a
     * command is dequeued and run. It says nothing about a command that is **never dequeued at all**.
     *
     * `flushOutgoing` awaits `channel.send(...)` inline on the driver loop with no bound, so a
     * datapath that stops answering parks the loop itself (see
     * `IdleTimeoutTerminationTests.theIdleTimerTerminatesAConnectionWhoseSendsNeverReturn`). A
     * `StreamSend` queued after that point is never dequeued, its deferred is never completed, and
     * the writer's own `withTimeout` fires into a `finally` that then joins that deferred
     * **NonCancellable** — the exact "permanent, uncancellable hang" this suite's KDoc names, reached
     * by a route the guard does not cover.
     *
     * That is what a 72-hour walk recorded: an iOS client echoing at full cadence (338 echoes, 47 ms
     * RTT) that then logged **nothing whatsoever for 48.6 hours** — not a read timeout, not a send
     * failure, not the idle timeout — while a heartbeat coroutine on another dispatcher kept writing
     * to the same file every 60 s. A write that hangs *below* its own deadline is the only shape that
     * produces silence rather than a logged failure.
     *
     * The assertion is deliberately only that the write **settles**. Succeeding and failing are both
     * fine — a bounded send would let the loop drain and the write complete, while a send that
     * reports failure would surface as a timeout — but a write that returns neither answer leaves the
     * caller with nothing to log, retry, or reconnect on. Pinning a particular outcome here would
     * over-constrain a fix; pinning *termination* is the actual contract.
     *
     * The fix belongs at the park, not at the join: the NonCancellable join is load-bearing — it is
     * what stops a caller freeing a buffer whose raw native address a queued command still carries
     * (the #366 / #401 send-path use-after-free). Bound the send and this route closes with it.
     */
    @Test
    fun aWriterWhoseCommandIsNeverDequeuedFailsInsteadOfWedging() =
        runQuicTest(timeout = 20.seconds) {
            val api = StubQuicheApi()
            api.established = true
            // The startup flush emits exactly one datagram and the channel parks on it, so the loop is
            // stuck *before* it can dequeue any stream command — no scheduler luck involved.
            api.connSendOnce = 1300
            val wedged = WedgedUdpChannel()
            val driver = createTestDriver(api, udpChannel = wedged)
            // Supervisor scope + swallow-all handler for the same reason as the tests above.
            val driverScope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
            driver.start(driverScope)
            val adapter = DriverStreamAdapter(driver, StreamSlot(QuicStreamId(0L)))
            val buf = bufferFactory.allocate(8)
            buf.writeString("ping", Charset.UTF8)
            buf.resetForRead()

            // The writer runs DETACHED from the test scope on purpose. A wedged write cannot be
            // cancelled — its `finally` joins the command's deferred under NonCancellable — so as a
            // child it would outlast the assertion, `runQuicTest`'s cap AND `runTest`'s own budget,
            // and a regression would hang the CI lane instead of failing it. Measured: as a child it
            // ran 10+ minutes past a 500ms deadline and only a `pkill` ended it. Abandoning it costs
            // nothing — a thread dump of that shape shows the coroutine suspended with every
            // dispatcher worker idle, holding no thread.
            val writerScope =
                CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
            val settled = CompletableDeferred<Result<Int>>()
            var everSettled = false
            try {
                // Anti-vacuity: without a parked send the loop is healthy and this test proves nothing.
                withTimeout(3.seconds) { while (wedged.sendCount == 0) yield() }

                writerScope.launch {
                    settled.complete(runCatching { adapter.streamWrite(QuicStreamId(0L), buf, WRITE_DEADLINE) })
                }

                val outcome = withTimeoutOrNull(WRITE_DEADLINE * 16) { settled.await() }
                everSettled = outcome != null
                assertNotNull(
                    outcome,
                    "a write whose command the parked loop will never dequeue never settled — it was " +
                        "still suspended ${WRITE_DEADLINE * 16} after a ${WRITE_DEADLINE} deadline, below " +
                        "its own timeout, where no caller can observe it. Hanging is worse than failing: " +
                        "the caller cannot log it, cannot retry, and cannot reconnect, so the connection " +
                        "is stranded with no record that anything went wrong — the 48.6-hour silence " +
                        "recorded on the 2026-09-03 walk.",
                )
            } finally {
                wedged.release()
                driver.destroy()
                driverScope.cancel()
                writerScope.cancel()
                // Freed only once the write has settled. While it is wedged the queued command still
                // carries this buffer's raw native address, and freeing under it is precisely the
                // send-path use-after-free the NonCancellable join exists to prevent (#366 / #401).
                if (everSettled) buf.freeNativeMemory()
            }
        }

    private companion object {
        /** The write deadline under test — short, so a hang is obvious and a pass is quick. */
        val WRITE_DEADLINE = 500.milliseconds
    }
}
