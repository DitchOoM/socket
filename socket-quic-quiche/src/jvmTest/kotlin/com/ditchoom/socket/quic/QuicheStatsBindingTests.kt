package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * W3 quiche stats bindings (RFC_DETERMINISTIC_SIMULATION.md §5.1 item 5), exercised end-to-end on
 * the FFM backend: a real-quiche [withSemanticSim] session does a handshake + stream echo over a
 * small real latency, then reads [QuicStatsSnapshot] ON the driver loop via [QuicheDriver.stats]
 * and asserts the counters are sane (rtt > 0 after acked round-trips, sent/recv/bytes > 0).
 *
 * Backend coverage note: this test loads the FFM binding on the local JDK 21 toolchain. The JNI
 * shim addition is compiled only by CI's build-linux; the cinterop binding runs on the Apple lane
 * (`AppleQuicheStatsBindingTest`) and mirrors verbatim to linux.
 */
class QuicheStatsBindingTests {
    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun semanticSim_echo_yields_sane_conn_and_path_stats() =
        runQuicTest(timeout = 30.seconds) {
            skipOnMissingNativeLib {
                withSemanticSim(
                    // Small REAL latency so quiche collects genuine RTT samples (its clock is
                    // internal `Instant::now()` — see the W4 virtual-time finding).
                    ImpairmentConfig(seed = 11L, latency = 2.milliseconds),
                    establishTimeout = 15.seconds,
                ) {
                    val payload = "stats probe payload"
                    val serverJob =
                        launch {
                            val stream = server.acceptStream()
                            val data = stream.read(10.seconds)
                            if (data is ReadResult.Data) {
                                stream.write(data.buffer, 10.seconds)
                                data.buffer.freeIfNeeded()
                            }
                            stream.close()
                        }
                    try {
                        val stream = client.openStream()
                        val sendBuf = BufferFactory.network().allocate(payload.length)
                        sendBuf.writeString(payload, Charset.UTF8)
                        sendBuf.resetForRead()
                        try {
                            stream.write(sendBuf, 10.seconds)
                        } finally {
                            sendBuf.freeNativeMemory()
                        }
                        val response = stream.read(10.seconds)
                        assertIs<ReadResult.Data>(response, "echo read returned no data")
                        assertEquals(payload, response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                        response.buffer.freeIfNeeded()
                        stream.close()

                        val snapshot = clientDriver.stats()
                        val conn = assertNotNull(snapshot.connStats, "connStats unbound on this backend")
                        assertTrue(conn.sent > 0, "conn.sent should count handshake+echo packets: $conn")
                        assertTrue(conn.recv > 0, "conn.recv should count handshake+echo packets: $conn")
                        assertTrue(conn.sentBytes > 0, "conn.sentBytes: $conn")
                        assertTrue(conn.recvBytes > 0, "conn.recvBytes: $conn")
                        assertTrue(conn.ackedBytes > 0, "echo round-trip must have acked bytes: $conn")
                        assertTrue(conn.pathsCount >= 1, "at least the primary path: $conn")

                        val path = assertNotNull(snapshot.pathStats, "path 0 must exist on an established connection")
                        assertTrue(path.rtt > Duration.ZERO, "rtt must be measured after acked round-trips: $path")
                        assertTrue(path.minRtt > Duration.ZERO, "minRtt after real samples: $path")
                        assertTrue(path.rtt >= path.minRtt, "rtt >= minRtt: $path")
                        assertTrue(path.cwnd > 0, "congestion window is never 0: $path")
                        assertTrue(path.pmtu > 0, "pmtu is never 0: $path")
                        assertTrue(path.sent > 0 && path.recv > 0, "path packet counters: $path")
                        assertTrue(path.sentBytes > 0 && path.recvBytes > 0, "path byte counters: $path")
                        assertTrue(path.active, "path 0 is the active path: $path")

                        // Server side binds identically (same api object) — spot-check via its driver.
                        val serverConn = assertNotNull(serverDriver.stats().connStats)
                        assertTrue(serverConn.recv > 0 && serverConn.sent > 0, "server counters: $serverConn")
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    @Test
    fun stats_after_teardown_is_all_null_not_a_crash() =
        runQuicTest(timeout = 30.seconds) {
            skipOnMissingNativeLib {
                val driver =
                    withSemanticSim(ImpairmentConfig(seed = 12L), establishTimeout = 15.seconds) {
                        clientDriver
                    }
                // The sim torn everything down (conn freed) — the command channel is closed, so
                // stats() must resolve to the typed empty snapshot instead of touching quiche.
                assertEquals(QuicStatsSnapshot(null, null), driver.stats())
            }
        }
}
