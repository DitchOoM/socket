package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.NetworkAvailability
import com.ditchoom.socket.quic.sim.SimNetworkMonitor
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import com.ditchoom.socket.transport.NetworkId
import com.ditchoom.socket.transport.NetworkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Peer [PathKey] token of a `v1 … DGRAM_OUT|DGRAM_IN <len> <pathKey> <hex>` trace line, or null
     * for any non-datagram line. Used to prove per-connection sink isolation without a connection id
     * (the v1 grammar has none) — a sink's datagrams must all share one peer path.
     */
    private fun datagramPathKey(line: String): String? {
        val tokens = line.split(' ')
        if (tokens.size < 5) return null
        return if (tokens[2] == "DGRAM_OUT" || tokens[2] == "DGRAM_IN") tokens[4] else null
    }

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /**
     * Suspend until the capture [lines] hold BOTH connectivity emissions the client made — the
     * `NET_ID` for [migratedTo] and the `NET_AVAIL` UNAVAILABLE. Lets the caller close the
     * connection only after the monitor tap has demonstrably recorded them, replacing a fixed
     * post-emission delay that raced connection teardown under CI load (the `observe()` collectors
     * live on the connection scope and die when it closes). Bounded by the caller's `withTimeout`:
     * if the tap is broken the predicate never holds and the test times out — a real failure, not a
     * flaky pass. Reads the concurrently-appended list under its own monitor to avoid a CME.
     */
    private suspend fun awaitConnectivityTapped(
        lines: List<String>,
        migratedTo: NetworkId,
    ) {
        // Bounded so a genuine tap failure fails HERE with a diagnostic, instead of hanging to the
        // outer timeout. The connection stays alive (we haven't closed) so the tap collectors can run.
        try {
            withTimeout(10.seconds) {
                while (true) {
                    val events = TraceEvent.parseAll(synchronized(lines) { lines.toList() })
                    val gotId = events.filterIsInstance<TraceEvent.Net>().any { it.id == migratedTo }
                    val gotAvail =
                        events.filterIsInstance<TraceEvent.NetAvail>().any {
                            it.value == NetworkAvailability.UNAVAILABLE
                        }
                    if (gotId && gotAvail) return@withTimeout
                    delay(10)
                }
            }
        } catch (_: TimeoutCancellationException) {
            val snapshot = synchronized(lines) { lines.toList() }
            error("connectivity tap never recorded networkId=$migratedTo + UNAVAILABLE within 10s: $snapshot")
        }
    }

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
                                trace = QuicTraceCapture(sink = { e -> lines += e.toString() }, networkMonitor = monitor),
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
                                    val echoed =
                                        if (response is ReadResult.Data) {
                                            response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                                        } else {
                                            "no_data"
                                        }
                                    // Wait until the engine's connectivity tap has folded BOTH monitor
                                    // emissions into the trace BEFORE signalling echoResult. The tap's
                                    // observe() collectors run on THIS connection's scope; the main
                                    // coroutine cancels this clientJob as soon as echoResult completes, so
                                    // completing before the tap has recorded lets teardown cancel the
                                    // collectors before they run — on a slow/contended runner they are
                                    // starved and record nothing (the intermittent CI failure). Gating
                                    // completion on the tap makes the teardown order deterministic.
                                    awaitConnectivityTapped(lines, migratedTo)
                                    echoResult.complete(echoed)
                                    stream.close()
                                }
                            }

                        try {
                            withTimeout(15.seconds) { echoResult.await() }
                        } finally {
                            clientJob.cancel()
                            serverJob.cancel()
                        }

                        val snapshot = synchronized(lines) { lines.toList() }
                        assertTrue(snapshot.isNotEmpty(), "capture opt-in recorded nothing")
                        assertTrue(snapshot.all { it.startsWith("v1 ") }, "every line is versioned: $snapshot")
                        assertTrue(snapshot.any { it.contains("DGRAM_OUT") }, "engine must record sent datagrams: $snapshot")
                        assertTrue(snapshot.any { it.contains("DGRAM_IN") }, "engine must record received datagrams")

                        val events = TraceEvent.parseAll(snapshot)
                        assertTrue(
                            events.filterIsInstance<TraceEvent.Net>().any { it.id == migratedTo },
                            "engine must tap NetworkMonitor.networkId into the trace: $snapshot",
                        )
                        assertTrue(
                            events.filterIsInstance<TraceEvent.NetAvail>().any {
                                it.value == NetworkAvailability.UNAVAILABLE
                            },
                            "engine must tap NetworkMonitor.availability into the trace: $snapshot",
                        )
                    }
                }
            }
        }

    @Test
    fun server_capture_mints_a_fresh_sink_per_accepted_connection() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(30.seconds) {
                    // Server-side capture: sinkFor mints a fresh sink per ACCEPTED connection, so two
                    // concurrent clients yield two independent, self-contained v1 traces. This is the
                    // property deterministic *server* replay needs — the v1 grammar carries no
                    // connection id, so one-sink-per-connection is the only way accepted connections
                    // stay separately replayable instead of interleaving onto a shared sink.
                    val sinks = Collections.synchronizedList(mutableListOf<MutableList<String>>())
                    val serverOptions =
                        baseOptions.copy(
                            trace =
                                QuicTraceCapture(
                                    sinkFor = {
                                        val lines = Collections.synchronizedList(mutableListOf<String>())
                                        sinks += lines
                                        TraceSink { e -> lines += e.toString() }
                                    },
                                ),
                        )

                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = serverOptions) {
                        val serverPort = port
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

                        suspend fun oneClient(payload: String): String {
                            val echoed = CompletableDeferred<String>()
                            withQuicConnection("localhost", serverPort, baseOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val sendBuf = BufferFactory.Default.allocate(payload.length)
                                sendBuf.writeString(payload, Charset.UTF8)
                                sendBuf.resetForRead()
                                stream.write(sendBuf, 5.seconds)
                                val response = stream.read(5.seconds)
                                echoed.complete(
                                    if (response is ReadResult.Data) {
                                        response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                                    } else {
                                        "no_data"
                                    },
                                )
                                // Let the server driver flush its final trace lines before close().
                                delay(150)
                                stream.close()
                            }
                            return echoed.await()
                        }

                        try {
                            val c1 = async(Dispatchers.IO) { oneClient("first-conn") }
                            val c2 = async(Dispatchers.IO) { oneClient("second-conn") }
                            assertEquals("first-conn", c1.await())
                            assertEquals("second-conn", c2.await())
                            // Beat for the server-side drivers to flush trailing DGRAM lines.
                            delay(250)
                        } finally {
                            serverJob.cancel()
                        }

                        // The invariant that makes deterministic server replay possible: each accepted
                        // connection records onto its OWN sink, and no sink ever interleaves two
                        // connections. We prove it via the per-datagram PathKey (peer address) — every
                        // DGRAM line on a sink must reference a single peer, and each distinct peer must
                        // land on exactly one sink (never split, never merged). Loopback also produces
                        // a couple of short-lived PROTOCOL_VIOLATION accepts with no datagrams; those
                        // correctly get their own (traffic-free) sinks and simply don't participate in
                        // the path-isolation check. (Server sinks record DGRAM_OUT only — inbound
                        // datagrams reach a server driver through the central demux loop, which bypasses
                        // the per-driver recording channel; that's orthogonal to per-connection routing.)
                        val perConn = sinks.map { it.toList() }
                        assertTrue(perConn.isNotEmpty(), "server capture minted no sinks at all")
                        perConn.forEachIndexed { i, s ->
                            assertTrue(s.isNotEmpty(), "sink #$i captured nothing: $perConn")
                            assertTrue(s.all { it.startsWith("v1 ") }, "sink #$i must be a versioned trace: $s")
                        }

                        // peer PathKey -> indices of sinks it appears on.
                        val pathToSinks = mutableMapOf<String, MutableSet<Int>>()
                        perConn.forEachIndexed { i, s ->
                            val paths = s.mapNotNull(::datagramPathKey).toSet()
                            assertTrue(paths.size <= 1, "sink #$i interleaves >1 connection's datagrams: $paths")
                            paths.forEach { p -> pathToSinks.getOrPut(p) { mutableSetOf() } += i }
                        }
                        assertTrue(
                            pathToSinks.size >= 2,
                            "expected >=2 distinct client connections captured on separate sinks, got ${pathToSinks.keys}",
                        )
                        pathToSinks.forEach { (path, idxs) ->
                            assertEquals(1, idxs.size, "client $path appears on multiple sinks $idxs (interleaved/split)")
                        }
                        // STATE lines carry the state's *qualified* class name (…QuicConnectionState.Established).
                        val established =
                            perConn.count { s ->
                                s.any { line ->
                                    (TraceEvent.parse(line) as? TraceEvent.State)?.name?.endsWith(".Established") == true
                                }
                            }
                        assertTrue(established >= 2, "expected >=2 fully-established server-side traces, got $established")
                    }
                }
            }
        }
}
