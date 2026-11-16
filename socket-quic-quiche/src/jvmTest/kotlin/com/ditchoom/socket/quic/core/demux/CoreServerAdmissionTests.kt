package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheRecvInfo
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.ReceiveBatch
import com.ditchoom.socket.quic.core.udp.ReceiveBatchResult
import com.ditchoom.socket.quic.loadQuicheApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/** What the server does when more handshakes start than a pass admits, and what a connection's `recv_info` costs. */
class CoreServerAdmissionTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    /** Client loops of this class's own, so connections it leaves ending never share a thread with another test's. */
    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aBurstOverTheBoundOfHandshakesPerPassIsHeldInTheBacklogForTheNextPassesAndNothingIsDropped() =
        runBlocking {
            val gate = GatedIo()
            val bound = 4
            withCoreEchoServer(
                ServerConfig(admission = AdmissionBounds(perPass = bound, inProgress = 1_024)),
                wrapIo = gate::around,
            ) { server ->
                val connects =
                    (0 until 4 * bound).map { n ->
                        async(Dispatchers.IO) {
                            val client = connectCore(server.port, loops = loops)
                            try {
                                val payload = Random(n).nextBytes(512)
                                payload to echoOver(client.connection.openStream(), payload)
                            } finally {
                                client.connection.close(QuicError.NoError)
                            }
                        }
                    }
                // Every first Initial waits in the socket; the next pass takes them all at once and starts them over its passes.
                delay(500.milliseconds)
                gate.open()
                val echoed = connects.awaitAll()
                echoed.forEachIndexed { n, (sent, back) -> assertContentEquals(sent, back, "connection $n") }
                assertEquals((4 * bound).toLong(), server.counts.accepted)
                assertEquals(0L, server.counts.droppedAdmission, "what was over a pass's bound waited in the backlog and was not dropped")
            }
        }

    @Test
    fun establishedPeersThatSendAgainBuildNoRecvInfoAndOnlyAcceptedConnectionsBuildOne() =
        runBlocking {
            val api = CountingApi(loadQuicheApi())
            val waves = 64
            val wave = 64
            withCoreEchoServer(ServerConfig(admission = AdmissionBounds(perPass = 256, inProgress = 1_024)), api = api) { server ->
                repeat(waves) { w ->
                    (0 until wave)
                        .map { n ->
                            async(Dispatchers.IO) {
                                val client = connectCore(server.port, loops = loops)
                                try {
                                    // Each peer sends again and again after it is established.
                                    val stream = client.connection.openStream()
                                    repeat(3) { round ->
                                        val payload = Random(w * wave + n + round).nextBytes(200)
                                        assertContentEquals(payload, echoOver(stream, payload))
                                    }
                                } finally {
                                    // Not close(): it waits out the closing period (three PTOs, about 250 ms on a fast
                                    // host and over a second on a CI emulator), which this test does not measure; the
                                    // end of the test still waits for every connection to be gone.
                                    client.connection.closeNow(QuicError.NoError)
                                }
                            }
                        }.awaitAll()
                }
                val peers = (waves * wave).toLong()
                assertEquals(peers, server.counts.accepted, "peers accepted")
                assertEquals(peers, server.counts.recvInfosBuilt, "recv_infos the server built: one per accepted connection")
                assertEquals(peers, api.recvInfos.get(), "recv_infos quiche was asked for: none for a datagram")
                println("[recv_info] $peers short-lived peers, ${api.recvInfos.get()} recv_infos")
                // Every connection ended, so every route and every connection is gone.
                var waited = 0
                while (server.parts.connections > 0 && waited < 150) {
                    delay(100.milliseconds)
                    waited++
                }
                assertEquals(0, server.parts.connections, "connections still routed after every peer left")
            }
        }

    /** Holds back what the socket has received until [open], so a burst is taken in one pass. */
    private class GatedIo {
        @Volatile private var gate = false

        fun open() {
            gate = true
        }

        fun around(real: DatagramBatchIo): DatagramBatchIo =
            object : DatagramBatchIo by real {
                override fun receive(into: ReceiveBatch): ReceiveBatchResult = if (gate) real.receive(into) else ReceiveBatchResult.Nothing
            }
    }

    private class CountingApi(
        private val real: QuicheApi,
    ) : QuicheApi by real {
        val recvInfos = AtomicLong()

        override fun recvInfoNew(
            fromAddr: Long,
            fromAddrLen: Int,
            toAddr: Long,
            toAddrLen: Int,
        ): QuicheRecvInfo {
            recvInfos.incrementAndGet()
            return real.recvInfoNew(fromAddr, fromAddrLen, toAddr, toAddrLen)
        }

        override fun connRecv(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            recvInfo: QuicheRecvInfo,
        ): Int = real.connRecv(conn, buf, bufLen, recvInfo)
    }
}
