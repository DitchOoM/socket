package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.QuicTraceEvent
import com.ditchoom.socket.quic.trace.QuicTraceParser
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end proof of the public capture opt-in ([QuicOptions.trace]) through the real
 * [QuicheEngine] via `withQuicConnection` (RFC_DETERMINISTIC_SIMULATION.md §5 coverage follow-up):
 * a consumer sets a [QuicTraceCapture] and receives BOTH QUIC-level traffic (DGRAM_*) and — because
 * it supplied a [com.ditchoom.socket.NetworkMonitor] — the client's connectivity state
 * (NET_AVAIL / NET_ID) on the same sink. Before this, `QuicheDriverTuning` was internal and no
 * consumer could turn capture on.
 */
class JvmQuicTraceCaptureTests {
    private val baseOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun publicOptIn_records_quic_traffic_and_taps_the_network_monitor() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = baseOptions) {
                        val echoResult = CompletableDeferred<String>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    val stream = acceptStream()
                                    val data = stream.read(5.seconds)
                                    if (data is ReadResult.Data) stream.write(data.buffer, 5.seconds)
                                    stream.close()
                                }
                            }
                        delay(100)

                        // Consumer-owned sink (thread-safe: the recorder emits from driver loops +
                        // the monitor collectors) + a settable monitor standing in for the platform one.
                        val lines = Collections.synchronizedList(mutableListOf<String>())
                        val monitor = SimNetworkMonitor(initial = NetworkAvailability.AVAILABLE)
                        val migratedTo = NetworkId.Link(NetworkKind.Wifi, 9L)
                        val clientOptions =
                            baseOptions.copy(
                                trace = QuicTraceCapture(sink = { line -> lines += line }, networkMonitor = monitor),
                            )

                        val clientJob =
                            launch(Dispatchers.IO) {
                                withQuicConnection("localhost", port, clientOptions, timeout = 10.seconds) {
                                    val stream = openStream()
                                    val sendBuf = BufferFactory.Default.allocate(11)
                                    sendBuf.writeString("hello quic!", Charset.UTF8)
                                    sendBuf.resetForRead()
                                    stream.write(sendBuf, 5.seconds)

                                    // Mid-flight connectivity change — the engine's observe() tap must
                                    // fold this into the SAME trace the DGRAM_* traffic goes to.
                                    monitor.setNetworkId(migratedTo)
                                    monitor.set(NetworkAvailability.UNAVAILABLE)

                                    val response = stream.read(5.seconds)
                                    if (response is ReadResult.Data) {
                                        echoResult.complete(response.buffer.readString(response.buffer.remaining(), Charset.UTF8))
                                    } else {
                                        echoResult.complete("no_data")
                                    }
                                    // Let the monitor collectors flush before close() cancels them.
                                    delay(200)
                                    stream.close()
                                }
                            }

                        try {
                            withTimeout(15.seconds) { echoResult.await() }
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }

                        val snapshot = lines.toList()
                        assertTrue(snapshot.isNotEmpty(), "capture opt-in recorded nothing")
                        assertTrue(snapshot.all { it.startsWith("v1 ") }, "every line is versioned: $snapshot")
                        assertTrue(snapshot.any { it.contains("DGRAM_OUT") }, "engine must record sent datagrams: $snapshot")
                        assertTrue(snapshot.any { it.contains("DGRAM_IN") }, "engine must record received datagrams")

                        val events = QuicTraceParser.parse(snapshot)
                        assertTrue(
                            events.filterIsInstance<QuicTraceEvent.Net>().any { it.id == migratedTo },
                            "engine must tap NetworkMonitor.networkId into the trace: $snapshot",
                        )
                        assertTrue(
                            events.filterIsInstance<QuicTraceEvent.NetAvail>().any {
                                it.value == NetworkAvailability.UNAVAILABLE
                            },
                            "engine must tap NetworkMonitor.availability into the trace: $snapshot",
                        )
                    }
                }
            }
        }
}
