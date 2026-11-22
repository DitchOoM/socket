package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicCloseException
import com.ditchoom.socket.quic.QuicCloseReason
import com.ditchoom.socket.quic.QuicConnectionState
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A server connection the core ends for a failure of its own tells its client, over a real loopback socket, on every
 * platform: the client's connect fails with the server's close, INTERNAL_ERROR, at once, not at its handshake bound.
 */
class CoreLocalFailureTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aServerWhoseFirstDatagramFailsThePacingClockJudgementTellsTheClient() =
        coreTest("CoreLocalFailureTests.aServerWhoseFirstDatagramFailsThePacingClockJudgementTellsTheClient") {
            withCoreServer(api = OtherClock(platformQuicheApi()), onConnection = { awaitCancellation() }) { server ->
                val began = TimeSource.Monotonic.markNow()
                // The server's handshake flight leaves before its close, so the connect may complete just before the end.
                val reason =
                    try {
                        val connection = connectCore(server.port, loops = loops).connection
                        val closed = withTimeout(TOLD_WITHIN) { connection.state.first { it is QuicConnectionState.Closed } }
                        assertIs<QuicConnectionState.Closed>(closed).reason
                    } catch (failed: QuicCloseException) {
                        failed.closeReason
                    }
                val took = began.elapsedNow()
                val byPeer = assertIs<QuicCloseReason.ByPeer>(reason, "the client read the server's close")
                assertEquals(INTERNAL_ERROR, byPeer.error.code)
                assertTrue(took < TOLD_WITHIN, "the client was told in $took, not at its handshake bound")
            }
        }

    /** A libquiche whose send times are on a clock an hour from the loop's: the first datagram fails the judgement. */
    private class OtherClock(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun sendInfoAtNanos(info: QuicheSendInfo): Long = inner.sendInfoAtNanos(info) + HOUR
    }

    private companion object {
        const val INTERNAL_ERROR = 0x1L
        const val HOUR = 3_600_000_000_000L
        val TOLD_WITHIN = 3.seconds
    }
}
