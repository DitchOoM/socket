@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.quicHarnessSkipReason
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.fixtures.TestCerts
import com.ditchoom.socket.testkit.fixtures.locateTestCerts
import com.ditchoom.socket.testkit.skip.recordSkip
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.errno
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.time.Duration.Companion.seconds

/**
 * Apple subclass of [WebTransportTestSuite]. The in-process QUIC server here is the Apple **quiche**
 * backend (provided transitively through socket-http3 → socket-quic-default → :socket-quic-quiche), so —
 * exactly like Linux's `withHttp3Server` — the server loads a loose PEM cert+key (no PKCS#12; that was an
 * NW `sec_identity_t` requirement, gone with the Network.framework backend in the quiche-on-Apple pivot).
 *
 * Cert paths are probed on the filesystem relative to the test's working directory, mirroring
 * [LinuxWebTransportTest]. macOS K/N runs the full suite; on iOS/tvOS/watchOS `--standalone` simulators
 * [wrapTestBody] reports a typed skip rather than returning green (see [quicHarnessSkipReason]).
 *
 * [multiplexed_twoSessionsOverOneConnection_eachRoundTrip] exercises the full HTTP/3 **server** stack on
 * the Apple quiche backend.
 */
class AppleWebTransportTest : WebTransportTestSuite() {
    private val certs = locateTestCerts(moduleDir = "socket-webtransport")

    override fun testTlsConfig(): QuicTlsConfig {
        val available =
            certs as? TestCerts.Available
                ?: error("testTlsConfig() reached with no fixtures; wrapTestBody should have skipped first")
        return QuicTlsConfig(
            certChainPath = available.certChainPath,
            privKeyPath = available.privKeyPath,
        )
    }

    override suspend fun openSingleSession(url: String): WebTransportSession =
        webTransportSupport().connect(url, loopbackClientConfig(clientTraceSink))

    override suspend fun openMultiplexed(url: String): MultiplexedWebTransport =
        (webTransportSupport() as WebTransportSupport.Multiplexed).connectMultiplexed(url, loopbackClientConfig(clientTraceSink))

    /**
     * Skip on `--standalone` Apple simulators (see [quicHarnessSkipReason]), or if the fixtures are
     * genuinely missing.
     *
     * ⚠️ The order matters, and this suite is NOT unblocked by #359. Its skips are
     * `simulator-lacks-network-services`, not `simulator-lacks-fixtures`: `simctl spawn --standalone`
     * runs outside `launchd_sim`, so the network daemons a connection needs are unreachable no matter
     * where the certs live. Exporting the cert path fixes the *second* of its two problems. This
     * suite starts running on simulators when a booted-mode lane exists (#81), not before — which is
     * why the three simulator shards still cannot set `SOCKET_REQUIRE_ALL_TESTS=1`.
     */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        val networkSkip = quicHarnessSkipReason()
        if (networkSkip != null) return recordSkip(AppleWebTransportTest::class, networkSkip)
        when (certs) {
            is TestCerts.Unavailable -> recordSkip(AppleWebTransportTest::class, certs.asSkipReason())
            is TestCerts.Available -> block()
        }
    }

    /**
     * Darwin's view of the port: `lsof` names every process holding it (and the family of each
     * socket — the #450 mechanism was an `AF_INET` daemon under a dual-stack `AF_INET6` server on one
     * port number), `netstat` adds each socket's Recv-Q (bytes delivered but not yet read, which
     * would put a datagram in the server's buffer with its loop asleep). Failure path only.
     */
    override suspend fun osSocketsOnPort(port: Int): String =
        shellLines("/usr/sbin/lsof -nP -iUDP:$port 2>&1")
            .map { "lsof    $it" }
            .plus(
                shellLines(
                    "/usr/sbin/netstat -an -p udp 2>&1",
                ).filter { it.contains(".$port ") || it.endsWith(".$port") }.map { "netstat $it" },
            ).joinToString("\n")
            .ifEmpty { "<no socket holds udp/$port>" }
}

/** Run [command] through `popen` and return its output lines. Diagnostics only — never on a happy path. */
private fun shellLines(command: String): List<String> {
    val fp = popen(command, "r") ?: return listOf("popen failed: errno=$errno")
    val out = StringBuilder()
    try {
        // Test code: the ByteArray discipline applies to production source sets only.
        val chunk = ByteArray(4096)
        chunk.usePinned { pinned ->
            while (fgets(pinned.addressOf(0), chunk.size, fp) != null) {
                out.append(pinned.addressOf(0).toKString())
            }
        }
    } finally {
        pclose(fp)
    }
    return out.lines().filter { it.isNotBlank() }
}

/**
 * Loopback client config (native [Http3WebTransportConfig], option 3): trust the self-signed test cert
 * via `verifyPeer = false`, and use a native-memory buffer factory so QUIC zero-copy stream I/O is
 * correct on Kotlin/Native. Mirrors the JVM/Linux subclasses.
 */
private fun loopbackClientConfig(trace: TraceSink) =
    Http3WebTransportConfig(
        quicOptions =
            QuicOptions(
                alpnProtocols = listOf(HTTP3_ALPN),
                verifyPeer = false,
                idleTimeout = 10.seconds,
                datagrams = DatagramOptions(),
                // Client half of the two-sided capture: an empty SERVER trace cannot distinguish a
                // client that sent nothing from one that sent into the void. See WebTransportDiagnostics.
                trace = QuicTraceCapture(trace),
            ),
        connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
    )
