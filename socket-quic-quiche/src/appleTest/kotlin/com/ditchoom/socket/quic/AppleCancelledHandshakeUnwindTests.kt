package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectPacing
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.udp.UdpSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A handshake cancelled at any instant must unwind promptly, whatever the NWConnection and the quiche
 * driver were doing when the cancel landed. The peer here never answers, so the handshake can only end
 * by cancellation.
 */
class AppleCancelledHandshakeUnwindTests {
    private val options = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 30.seconds)

    private suspend fun cancelAfter(
        jitterYields: Int,
        jitterMicros: Long,
        peer: QuicPeer,
        config: TransportConfig,
    ): Boolean {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val work = scope.async { withQuicConnection(peer, options, config, timeout = 30.seconds) { error("unreachable") } }
        repeat(jitterYields) { yield() }
        if (jitterMicros > 0) delay(jitterMicros.milliseconds / 1000)
        work.cancel()
        return withTimeoutOrNull(5.seconds) { work.join() } != null
    }

    @Test
    fun aSingleCandidateCancelledAtAnyInstantUnwinds() =
        runQuicTest(timeout = 240.seconds) {
            val silent = UdpSocket.bind("127.0.0.1", 0)
            try {
                val peer =
                    QuicPeer.Candidates(
                        listOf(QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), silent.localAddress.port)),
                        "localhost",
                    )
                val rnd = Random(7)
                var hung = 0
                repeat(ITERATIONS) { i ->
                    if (!cancelAfter(rnd.nextInt(0, 300), rnd.nextLong(0, 15_000), peer, TransportConfig())) {
                        hung++
                        println("[CancelProbe] single: iteration $i did not unwind within 5s")
                    }
                }
                assertEquals(0, hung, "cancelled single-candidate handshakes that did not unwind, of $ITERATIONS")
            } finally {
                silent.close()
            }
        }

    @Test
    fun twoRacedCandidatesCancelledAtAnyInstantUnwind() =
        runQuicTest(timeout = 240.seconds) {
            val silent = UdpSocket.bind("127.0.0.1", 0)
            try {
                val port = silent.localAddress.port
                val peer =
                    QuicPeer.Candidates(
                        listOf(
                            QuicEndpoint(ResolvedAddress("2001:db8::1", IpFamily.V6), port),
                            QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), port),
                        ),
                        "localhost",
                    )
                val config = TransportConfig(connectPacing = ConnectPacing.Simultaneous)
                val rnd = Random(11)
                var hung = 0
                repeat(ITERATIONS) { i ->
                    if (!cancelAfter(rnd.nextInt(0, 300), rnd.nextLong(0, 15_000), peer, config)) {
                        hung++
                        println("[CancelProbe] raced: iteration $i did not unwind within 5s")
                    }
                }
                assertEquals(0, hung, "cancelled raced handshakes that did not unwind, of $ITERATIONS")
            } finally {
                silent.close()
            }
        }

    private companion object {
        const val ITERATIONS = 200
    }
}
