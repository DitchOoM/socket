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
import kotlin.time.Duration.Companion.seconds

/**
 * A server connection the core ends for a failure of its own tells its client, over a real loopback socket, on every
 * platform: the client's connect fails with the server's close, INTERNAL_ERROR, not with its own handshake bound.
 */
class CoreLocalFailureTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aServerWhoseFirstDatagramFailsThePacingClockJudgementTellsTheClient() =
        coreTest("CoreLocalFailureTests.aServerWhoseFirstDatagramFailsThePacingClockJudgementTellsTheClient") {
            withCoreServer(api = OtherClock(platformQuicheApi()), onConnection = { awaitCancellation() }) { server ->
                // The server's handshake flight leaves before its close, so the connect may complete just before the end.
                val reason =
                    try {
                        val connection = connectCore(server.port, loops = loops).connection
                        val closed = withTimeout(STATE_WITHIN) { connection.state.first { it is QuicConnectionState.Closed } }
                        assertIs<QuicConnectionState.Closed>(closed).reason
                    } catch (failed: QuicCloseException) {
                        failed.closeReason
                    }
                // Had the close not reached it, the client would end at its own handshake bound, not by its peer.
                val byPeer = assertIs<QuicCloseReason.ByPeer>(reason, "the client read the server's close, not its own bound")
                assertEquals(INTERNAL_ERROR, byPeer.error.code)
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

        /** Past the client's handshake bound and idle timeout: a missed close shows as its own end, not as this timeout. */
        val STATE_WITHIN = 30.seconds
    }
}
