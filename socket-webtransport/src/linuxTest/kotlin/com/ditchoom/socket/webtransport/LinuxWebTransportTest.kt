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

    override suspend fun openSingleSession(url: String): WebTransportSession = webTransportSupport().connect(url, loopbackClientConfig())

    override suspend fun openMultiplexed(url: String): MultiplexedWebTransport =
        (webTransportSupport() as WebTransportSupport.Multiplexed).connectMultiplexed(url, loopbackClientConfig())
}

/**
 * Loopback client config (native [Http3WebTransportConfig], option 3): trust the self-signed test cert
 * via `verifyPeer = false`, and use a native-memory buffer factory so QUIC zero-copy stream I/O is
 * correct on Kotlin/Native. Mirrors the JVM subclass.
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
