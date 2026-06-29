package com.ditchoom.socket.webtransport

import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.http3.HTTP3_ALPN
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTlsConfig
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Android instrumented subclass of [WebTransportTestSuite]. Android runs the same quiche backing as the
 * JVM (the Multiplexed provider over :socket-http3), so the only platform glue is cert resolution:
 * quiche loads the TLS chain + key from a **filesystem path**, but on Android the bundled resources live
 * inside the test APK rather than on disk, so we extract `certs/cert.{crt,key}` (from
 * `src/androidInstrumentedTest/resources/certs/`) into the instrumentation cache dir and hand quiche the
 * real paths — mirroring :socket-quic-quiche's `AndroidTestCerts` / `AndroidQuicLoopbackTests`.
 *
 * The quiche JNI `.so` (`libquiche_jni.so`) is merged into this test APK transitively from
 * :socket-quic-quiche's `androidMain/jniLibs`, so the in-process QUIC server links and runs on-device —
 * which is why the test keeps the default pass-through [wrapTestBody] (no `UnsatisfiedLinkError` to skip).
 *
 * Runs on a connected device/emulator (`./gradlew :socket-webtransport:connectedAndroidTest`); the v6
 * Phase-4 DONE bar is [multiplexed_twoSessionsOverOneConnection_eachRoundTrip].
 */
class AndroidWebTransportTest : WebTransportTestSuite() {
    private val cacheDir: File by lazy {
        InstrumentationRegistry.getInstrumentation().context.cacheDir
    }

    private fun certPath(name: String): String {
        val out = File(cacheDir, name)
        val cl = AndroidWebTransportTest::class.java.classLoader ?: error("no classloader")
        val resource = "certs/$name"
        val input =
            cl.getResourceAsStream(resource)
                ?: error("Bundled test cert not found on classpath: $resource")
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        return out.absolutePath
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
 * correct everywhere. Mirrors the JVM/Linux/Apple subclasses.
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
