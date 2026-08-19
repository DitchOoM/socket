package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
}
