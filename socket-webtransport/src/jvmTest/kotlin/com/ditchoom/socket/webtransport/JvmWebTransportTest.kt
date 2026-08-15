package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.time.Duration.Companion.seconds

/**
 * JVM subclass of [WebTransportTestSuite]. Resolves the test cert/key from the jvmTest classpath
 * (`certs/`), and converts the `UnsatisfiedLinkError` thrown by the lazy quiche FFM binding (on hosts
 * where `libquiche` isn't staged) into a JUnit assumption so the tests skip rather than fail. Mirrors
 * socket-http3's `JvmHttp3LoopbackTest`.
 */
class JvmWebTransportTest : WebTransportTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
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

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(block)
}

/**
 * Loopback client config (native [Http3WebTransportConfig], option 3): trust the self-signed test cert
 * via `verifyPeer = false` — the suite exercises WebTransport, not chain validation, matching the
 * QUIC/h3 loopback suites — and use a native-memory buffer factory so QUIC zero-copy stream I/O is
 * correct on every platform.
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
