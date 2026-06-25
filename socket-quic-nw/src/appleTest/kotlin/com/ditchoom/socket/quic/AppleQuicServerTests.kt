package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Apple (Network.framework) run of the shared [QuicServerTestSuite] — the first in-process
 * client↔server QUIC round-trip on Apple K/N (issue #112). Before the NWListener server existed,
 * the shared server suite couldn't run on Apple at all.
 */
class AppleQuicServerTests : QuicServerTestSuite() {
    override fun testTlsConfig() = appleQuicTestTlsConfig()

    override fun localhostTlsConfig() = appleQuicLocalhostTlsConfig()

    override fun localhostCertPem() = appleReadFileText(appleTestCertPath("localhost.crt"))

    override fun unrelatedCaPem() = appleReadFileText(appleTestCertPath("cert.crt"))

    /**
     * Skip on Apple simulators launched in `--standalone` mode, where Network.framework's QUIC
     * datapath is unreachable (see [shouldSkipQuicHarnessOnSimulator]). macOS K/N runs the full
     * suite; the iOS simulator runs it only under the booted mode CI enables.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }

    /**
     * Network.framework surfaces a peer RESET_STREAM distinctly as [ReadResult.Reset]
     * (unlike the quiche driver, which collapses it to EOF). This is the regression guard
     * for issue #81: before the fix, [NWQuicByteStream] didn't implement [com.ditchoom.buffer.flow.Resettable],
     * so [QuicByteStream.reset] degraded to a graceful FIN and the peer saw `End`, not `Reset`.
     */
    override fun assertResetObservedByPeer(resultClassName: String?) {
        kotlin.test.assertEquals(
            "Reset",
            resultClassName,
            "Apple must surface a peer RESET_STREAM as ReadResult.Reset, not a graceful close",
        )
    }

    /**
     * NW override of the connection-close-code test. Network.framework closes a connection by
     * cancelling its `nw_connection_group` — a **local** teardown that does not put a QUIC
     * application close code (RFC 9000 §19.19) on the wire. So unlike the quiche backends, a peer
     * cannot observe the close code (the same family of limitation as #134, where NW also can't
     * surface peer stream error codes). The quiche-backed subclasses carry the peer-observation
     * regression guard; this override asserts the only contract NW can honor:
     * [QuicScope.closeWithError] is wired on a server-accepted NW connection (it resolves to the
     * [QuicConnection] default `close(ApplicationError)` — there is no NW-specific override) and
     * completes the teardown without throwing or hanging.
     *
     * This is a real assertion, not a silent skip: a regression that broke `closeWithError` on the
     * NW server scope (e.g. dropping the [QuicConnection] default → `UnsupportedOperationException`)
     * fails here.
     */
    @Test
    override fun connectionCloseWithErrorIsObservedByPeer() =
        runQuicTest {
            wrapTestBody {
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = testQuicOptions) {
                    val serverClosedCleanly = CompletableDeferred<Boolean>()
                    val serverJob =
                        launch {
                            connections {
                                val stream = acceptStream()
                                stream.read(5.seconds)
                                try {
                                    closeWithError(0xBEEFL)
                                    serverClosedCleanly.complete(true)
                                } catch (t: Throwable) {
                                    serverClosedCleanly.completeExceptionally(t)
                                }
                            }
                        }
                    delay(100)

                    try {
                        withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                            val stream = openStream()
                            val hello = BufferFactory.deterministic().allocate(5)
                            hello.writeString("hello", Charset.UTF8)
                            hello.resetForRead()
                            stream.write(hello, 5.seconds)
                            // NW doesn't propagate the close code to the peer; assert only that the
                            // server's closeWithError completed the teardown cleanly within the timeout.
                            assertTrue(withTimeout(10.seconds) { serverClosedCleanly.await() })
                        }
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }
}
