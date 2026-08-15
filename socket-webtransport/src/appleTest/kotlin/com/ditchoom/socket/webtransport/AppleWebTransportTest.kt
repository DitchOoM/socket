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
import com.ditchoom.socket.testkit.skip.recordSkip
import com.ditchoom.socket.testkit.trace.TraceSink
import platform.posix.F_OK
import platform.posix.access
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

    /** Skip on `--standalone` Apple simulators (see [quicHarnessSkipReason]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        val skip = quicHarnessSkipReason()
        if (skip != null) return recordSkip(skip)
        block()
    }
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
