@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import platform.posix.F_OK
import platform.posix.access
import kotlin.time.Duration.Companion.seconds

/**
 * Apple subclass of [WebTransportTestSuite]. The in-process QUIC server here is Network.framework's
 * listener (provided transitively through socket-http3 → socket-quic-default → :socket-quic-nw), so —
 * unlike Linux's `withHttp3Server`, which talks quiche — the server identity must be a PKCS#12 bundle
 * NW can import into a `sec_identity_t` (loose PEM cert+key cannot build one; see [QuicTlsConfig.pkcs12Path]).
 * The `generateWebTransportTestP12` Gradle task exports `testcerts/cert.p12` (passphrase `testpass`) from
 * the committed `cert.{crt,key}` and the Apple K/N test tasks depend on it.
 *
 * Cert paths are probed on the filesystem relative to the test's working directory, mirroring
 * [LinuxWebTransportTest]. Native targets keep the default pass-through [wrapTestBody] — the NW binding is
 * fixed at compile time, so there is no `UnsatisfiedLinkError` to translate into a skip.
 *
 * This is the first automated exercise of the full HTTP/3 **server** stack on Apple/Network.framework:
 * [multiplexed_twoSessionsOverOneConnection_eachRoundTrip] is the v6 Phase-4 DONE bar.
 */
class AppleWebTransportTest : WebTransportTestSuite() {
    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-webtransport/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates — did generateWebTransportTestP12 run?)")
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
            // Network.framework's QUIC listener imports its identity from this PKCS#12 blob; the PEM
            // paths above are ignored by the Apple server (kept for cross-platform config symmetry).
            pkcs12Path = certPath("cert.p12"),
            pkcs12Password = "testpass",
        )

    override suspend fun openSingleSession(url: String): WebTransportSession = webTransportSupport().connect(url, loopbackClientConfig())

    override suspend fun openMultiplexed(url: String): MultiplexedWebTransport =
        (webTransportSupport() as WebTransportSupport.Multiplexed).connectMultiplexed(url, loopbackClientConfig())
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
