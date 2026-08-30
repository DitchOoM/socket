package com.ditchoom.socket.testsuite.harness

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.data.readBuffer
import com.ditchoom.data.writeString
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.SSLSocketException
import com.ditchoom.socket.SocketClosedException
import com.ditchoom.socket.SocketTimeoutException
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.connect
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.runQuicTest
import com.ditchoom.socket.quic.scaled
import com.ditchoom.socket.quic.withQuicConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * W6 validation of the consumer-facing harness control plane: `withNetworkHarness`
 * resolves the controller's `/describe` manifest and drives real scenarios through
 * the typed accessors.
 *
 * Follows the harness discipline end to end:
 *  - **skip-on-unreachable** — with the docker stack down every test is a clean
 *    no-op (a printed skip line, never a failure);
 *  - **wall-clock runner** — [runQuicTest] (real dispatcher, no virtual-time
 *    fast-forwarding) because these tests do real network I/O;
 *  - deadlines are [scaled] by `QUIC_TEST_TIME_SCALE`, assertions are not.
 *
 * Published in `commonMain` (like the other suites in this module) so consumers can
 * subclass it from their own `commonTest` to validate their harness deployment.
 * In-repo it is materialized on every target by `NetworkHarnessTests` in `commonTest`.
 */
abstract class NetworkHarnessTestSuite {
    private suspend fun echoRoundTrip(
        endpoint: HarnessEndpoint,
        payload: String,
    ): String =
        ClientSocket.connect(
            port = endpoint.port,
            hostname = endpoint.host,
            config = TransportConfig(connectTimeout = 5.seconds.scaled),
        ) { socket ->
            socket.writeString(payload, Charset.UTF8, 5.seconds.scaled)
            val buf = socket.readBuffer(5.seconds.scaled)
            buf.readString(buf.remaining(), Charset.UTF8)
        }

    /** Manifest resolves and a payload echoes through the `echo()` accessor. */
    @Test
    fun manifestResolvesAndEchoRoundTrips() =
        runQuicTest {
            val payload = "w6-withNetworkHarness-echo"
            val ran =
                withNetworkHarness {
                    val endpoint = echo()
                    val echoed = echoRoundTrip(endpoint, payload)
                    assertTrue(
                        echoed.startsWith(payload),
                        "expected echoed payload to start with '$payload', got '$echoed'",
                    )
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — manifestResolvesAndEchoRoundTrips skipped")
        }

    /**
     * An `impaired(latency = 200 ms)` round-trip is measurably slower than direct.
     * The latency toxic adds a fixed 200 ms to every downstream chunk, so asserting
     * `>= 150 ms` is conservative (immune to scheduler noise) without being vacuous
     * — the direct round-trip on loopback completes in single-digit milliseconds.
     */
    @Test
    fun impairedLatencyRoundTripMeasurablySlower() =
        runQuicTest(timeout = 30.seconds) {
            val ran =
                withNetworkHarness {
                    val direct = measureTime { echoRoundTrip(echo(), "w6-direct") }
                    var impairedElapsed: Duration = Duration.ZERO
                    impaired(latency = 200.milliseconds) { proxy ->
                        impairedElapsed = measureTime { echoRoundTrip(proxy, "w6-impaired") }
                    }
                    println("[NetworkHarnessTestSuite] direct=$direct impaired=$impairedElapsed")
                    assertTrue(
                        impairedElapsed >= 150.milliseconds,
                        "impaired(latency=200ms) round-trip took $impairedElapsed — expected >= 150ms " +
                            "(direct took $direct)",
                    )
                    assertTrue(
                        impairedElapsed > direct,
                        "impaired round-trip ($impairedElapsed) not slower than direct ($direct)",
                    )
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — impairedLatencyRoundTrip skipped")
        }

    /**
     * Skip semantics are deterministic regardless of stack state: pointing at a port
     * nothing listens on must return `false` without running the block — and without
     * throwing. (TCP port 9 / discard is never bound on CI or dev machines.)
     */
    @Test
    fun skipsCleanlyWhenControllerUnreachable() =
        runQuicTest {
            var executed = false
            val ran =
                withNetworkHarness(controllerPort = 9) {
                    executed = true
                }
            assertFalse(ran, "withNetworkHarness claimed to run against a dead controller port")
            assertFalse(executed, "block executed despite unreachable controller")
        }

    /**
     * The [NetworkHarnessScope.tls] accessor resolves the cert-matrix vhosts: an EXPIRED cert is
     * rejected by default validation ([SSLSocketException], independent of whether the harness CA is
     * trusted), and the same matrix's VALID cert completes a handshake under `tlsInsecure()`. Mirrors
     * the root module's `TlsConformanceTests` rejection/insecure pairs, driven through the accessor.
     *
     * Skip-safe: absent from the manifest (a fixture without the TLS vhosts) → the whole block no-ops.
     */
    @Test
    fun tlsAccessorRejectsExpiredCertAndAcceptsValidWithInsecure() =
        runQuicTest {
            val ran =
                withNetworkHarness {
                    val expired = manifest.scenarioOrNull(TlsScenario.EXPIRED.manifestKey)
                    val valid = manifest.scenarioOrNull(TlsScenario.VALID.manifestKey)
                    if (expired == null || valid == null) {
                        println("[NetworkHarnessTestSuite] tls cert-matrix absent — tlsAccessor test skipped")
                        return@withNetworkHarness
                    }
                    // Default validation must reject the backdated cert regardless of CA trust.
                    assertFailsWith<SSLSocketException> {
                        val ep = tls(TlsScenario.EXPIRED)
                        ClientSocket.connect(
                            port = ep.port,
                            hostname = ep.host,
                            config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds.scaled),
                        ) { /* handshake must throw before the lambda runs */ }
                    }
                    // The valid vhost speaks TLS; tlsInsecure() completes the handshake without CA setup.
                    val validEp = tls(TlsScenario.VALID)
                    ClientSocket.connect(
                        port = validEp.port,
                        hostname = validEp.host,
                        config = TransportConfig.tlsInsecure().copy(connectTimeout = 5.seconds.scaled),
                    ) { socket ->
                        assertTrue(socket.isOpen, "tls(VALID) + tlsInsecure() should complete the handshake")
                    }
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — tlsAccessor test skipped")
        }

    /**
     * The [NetworkHarnessScope.quicEcho] accessor resolves the QUIC/UDP echo server and a stream
     * round-trips. `verifyPeer = false` accepts the harness's self-signed peer cert directly — this
     * exercises the QUIC client API surface, not cert validation (mirrors `QuicHarnessIntegrationTests`).
     *
     * Skip-safe: no `quic-echo` scenario (e.g. a fixture without the UDP server) → the block no-ops.
     * Where the harness is reachable the native quiche binding is present (the echo server is quiche
     * itself), so no native-missing guard is needed here.
     *
     * The send buffer is allocated **per the connection's declared
     * [com.ditchoom.socket.quic.QuicScope.capabilities]** — the #502 contract. Where
     * `requiresNativeMemoryBuffers` holds (every quiche backend, on every platform) it comes from the
     * scope's own `bufferFactory`; otherwise the portable `BufferFactory.Default` is fine. This is
     * the test that found #502: its first Linux-native run (PR #501) got past the handshake and died
     * on the first `stream.write()` of a `BufferFactory.Default` buffer with a `NullPointerException`
     * from the driver's `nativeMemoryAccess!!`, and until the capability existed it had to skip on
     * Kotlin/Native. It runs everywhere now; that a heap buffer is *rejected by type* rather than
     * crashing is [com.ditchoom.socket.quic.QuicServerTestSuite]'s to prove on every backend.
     */
    @Test
    fun quicEchoAccessorStreamRoundTrips() =
        runQuicTest(timeout = 30.seconds) {
            val ran =
                withNetworkHarness {
                    val ep =
                        manifest.scenarioOrNull("quic-echo") ?: run {
                            println("[NetworkHarnessTestSuite] quic-echo absent — quicEchoAccessor test skipped")
                            return@withNetworkHarness
                        }
                    // Confirm the accessor points at the same endpoint the manifest resolved.
                    assertTrue(quicEcho() == ep, "quicEcho() must resolve the manifest's quic-echo endpoint")
                    val payload = "w6-quic-echo"
                    withQuicConnection(
                        hostname = ep.host,
                        port = ep.port,
                        // ALPN must match QuicEchoTestServer.alpnProtocols ("test").
                        quicOptions = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds),
                        timeout = 10.seconds.scaled,
                    ) {
                        val stream = openStream()
                        // Allocate as the connection says, not as the platform happens to: the scope's
                        // factory is native-memory-backed wherever that is required, and Default is the
                        // portable choice wherever it is not. Freed after the write — write is zero-copy
                        // and takes no ownership.
                        val sendFactory = if (capabilities.requiresNativeMemoryBuffers) bufferFactory else BufferFactory.Default
                        val sendBuf = sendFactory.allocate(payload.length)
                        try {
                            sendBuf.writeString(payload, Charset.UTF8)
                            sendBuf.resetForRead()
                            stream.write(sendBuf, 5.seconds.scaled)
                        } finally {
                            sendBuf.freeIfNeeded()
                        }
                        val response = stream.read(5.seconds.scaled)
                        assertTrue(response is ReadResult.Data, "quic-echo returned no data")
                        // read transfers ownership; the pooled chunk goes back once decoded.
                        val echoed =
                            try {
                                response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                            } finally {
                                response.buffer.freeIfNeeded()
                            }
                        assertTrue(echoed.startsWith(payload), "expected echo of '$payload', got '$echoed'")
                        stream.close()
                    }
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — quicEchoAccessor test skipped")
        }

    /**
     * The [NetworkHarnessScope.rst] deterministic peer-close sidecar: after the client's first byte the
     * server sets `SO_LINGER=0` and closes → RST, so a parked read surfaces a [SocketClosedException]
     * (the root module's `pendingReadDuringPeerReset` contract, driven through the accessor).
     *
     * Skip-safe: the `rst` sidecar is Linux-CI-only (`network_mode: host`); absent from the manifest on
     * the macOS/native fixture (and on dev Macs), the block no-ops.
     */
    @Test
    fun rstAccessorSurfacesSocketClosedException() =
        runQuicTest(timeout = 30.seconds) {
            val ran =
                withNetworkHarness {
                    val ep =
                        manifest.scenarioOrNull("rst") ?: run {
                            println("[NetworkHarnessTestSuite] rst sidecar absent — rstAccessor test skipped")
                            return@withNetworkHarness
                        }
                    assertTrue(rst() == ep, "rst() must resolve the manifest's rst endpoint")
                    val socket =
                        ClientSocket.connect(
                            port = ep.port,
                            hostname = ep.host,
                            config = TransportConfig(connectTimeout = 5.seconds.scaled),
                        )
                    try {
                        coroutineScope {
                            // Park the read; UNDISPATCHED runs it up to the kernel-side read before returning.
                            val readResult =
                                async<Throwable?>(start = CoroutineStart.UNDISPATCHED) {
                                    try {
                                        socket.readBuffer(5.seconds.scaled)
                                        null
                                    } catch (t: Throwable) {
                                        t
                                    }
                                }
                            // Keep writing 1-byte triggers until the read observes the close (the sidecar
                            // RSTs after the first byte), or our own write hits the closed peer first.
                            while (!readResult.isCompleted) {
                                try {
                                    socket.writeString("x", Charset.UTF8, 1.seconds.scaled)
                                } catch (_: SocketClosedException) {
                                    break
                                }
                                yield()
                            }
                            val thrown = readResult.await()
                            assertNotNull(thrown, "expected the parked read to throw after peer reset")
                            assertTrue(
                                thrown is SocketClosedException,
                                "expected SocketClosedException, got ${thrown::class.simpleName}(${thrown.message})",
                            )
                        }
                    } finally {
                        socket.close()
                    }
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — rstAccessor test skipped")
        }

    /**
     * The [NetworkHarnessScope.blackhole] endpoint answers ARP and accepts the SYN, but an egress `tc`
     * filter drops every TCP packet it sends from its port (the SYN-ACK), so the client retransmits
     * until its connect deadline fires. A deadline expiry is [TimeoutCancellationException] on the JVM
     * (kotlinx's own, leaked from `withTimeout`) and [SocketTimeoutException] on the backends that map
     * it; anything else — a completed connect, or `NoRouteToHostException` from a blackhole that has
     * stopped answering ARP, which is what the first CI run of this test found (PR #501) — fails.
     *
     * Skip-safe: the filter needs `NET_ADMIN` + `tc`; absent from the fixture manifest, the block
     * no-ops.
     */
    @Test
    fun blackholeAccessorConnectTimesOut() =
        runQuicTest(timeout = 30.seconds) {
            val ran =
                withNetworkHarness {
                    if (manifest.scenarioOrNull("blackhole") == null) {
                        println("[NetworkHarnessTestSuite] netem blackhole absent — blackholeAccessor test skipped")
                        return@withNetworkHarness
                    }
                    blackhole { ep ->
                        val deadline = 2.seconds.scaled
                        val expired =
                            try {
                                ClientSocket.connect(
                                    port = ep.port,
                                    hostname = ep.host,
                                    config = TransportConfig(connectTimeout = deadline),
                                ) { /* unreachable: the SYN-ACK is dropped, connect times out */ }
                                null
                            } catch (t: TimeoutCancellationException) {
                                t
                            } catch (t: SocketTimeoutException) {
                                t
                            }
                        assertNotNull(
                            expired,
                            "connect to the blackhole ${ep.host}:${ep.port} completed inside $deadline — " +
                                "its SYN-ACK was not dropped",
                        )
                    }
                }
            if (!ran) println("[NetworkHarnessTestSuite] harness down — blackholeAccessor test skipped")
        }
}
