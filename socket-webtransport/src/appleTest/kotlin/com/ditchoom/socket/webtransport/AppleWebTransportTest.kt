@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.shouldSkipQuicHarnessOnSimulator
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
 * [LinuxWebTransportTest]. macOS K/N runs the full suite; iOS/tvOS/watchOS `--standalone` simulators lack
 * the `testcerts/` cwd, so [wrapTestBody] skips there (see [shouldSkipQuicHarnessOnSimulator]).
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

    override suspend fun openSingleSession(url: String): WebTransportSession = webTransportSupport().connect(url, loopbackClientConfig())

    override suspend fun openMultiplexed(url: String): MultiplexedWebTransport =
        (webTransportSupport() as WebTransportSupport.Multiplexed).connectMultiplexed(url, loopbackClientConfig())

    /** Skip on `--standalone` Apple simulators (see [shouldSkipQuicHarnessOnSimulator]). */
    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        if (shouldSkipQuicHarnessOnSimulator()) return
        block()
    }
}

/**
 * Loopback client config (native [Http3WebTransportConfig], option 3): trust the self-signed test cert
 * via `verifyPeer = false`, and use a native-memory buffer factory so QUIC zero-copy stream I/O is
 * correct on Kotlin/Native. Mirrors the JVM/Linux subclasses.
 */
private fun loopbackClientConfig() =
    Http3WebTransportConfig(
        quicOptions =
            QuicOptions(
                alpnProtocols = listOf(HTTP3_ALPN),
                verifyPeer = false,
                idleTimeout = 10.seconds,
                datagrams = DatagramOptions(),
            ),
        connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic()),
    )
