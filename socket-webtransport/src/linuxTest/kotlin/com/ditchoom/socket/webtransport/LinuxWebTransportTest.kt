@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.errno
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.time.Duration.Companion.seconds

/**
 * linuxX64 subclass of [WebTransportTestSuite]. The test binary whole-archives `libquiche.a`, so the
 * in-process QUIC server links and runs natively. Cert/key paths are probed on the filesystem relative
 * to the test's working directory, mirroring socket-http3's `LinuxHttp3LoopbackTest`. Native targets
 * keep the default pass-through [wrapTestBody] — the cinterop binding is fixed at compile time, so
 * there's no `UnsatisfiedLinkError` to translate.
 */
class LinuxWebTransportTest : WebTransportTestSuite() {
    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-webtransport/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override suspend fun openSingleSession(url: String): WebTransportSession =
        webTransportSupport().connect(url, loopbackClientConfig(clientTraceSink))

    override suspend fun openMultiplexed(url: String): MultiplexedWebTransport =
        (webTransportSupport() as WebTransportSupport.Multiplexed).connectMultiplexed(url, loopbackClientConfig(clientTraceSink))

    /**
     * The kernel's view of the port: `ss` names every UDP socket on it with its owning process,
     * family and Recv-Q; the `/proc/net/udp{,6}` rows are the same table with no tool dependency
     * (`ss` is not guaranteed on a minimal container), port in hex. Failure path only.
     */
    override suspend fun osSocketsOnPort(port: Int): String {
        val hexPort = ":" + port.toString(16).uppercase().padStart(4, '0')
        val ss = shellLines("ss -uapn 2>&1").filter { it.contains(":$port ") }.map { "ss      $it" }
        val proc =
            shellLines("cat /proc/net/udp /proc/net/udp6 2>&1")
                .filter { it.contains(hexPort) }
                .map { "proc    $it" }
        return (ss + proc).joinToString("\n").ifEmpty { "<no socket holds udp/$port>" }
    }
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
 * correct on Kotlin/Native. Mirrors the JVM subclass.
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
