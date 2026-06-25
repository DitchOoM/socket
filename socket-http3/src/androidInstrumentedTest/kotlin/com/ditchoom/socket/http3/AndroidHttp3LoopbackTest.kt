package com.ditchoom.socket.http3

import androidx.test.platform.app.InstrumentationRegistry
import com.ditchoom.socket.quic.QuicTlsConfig
import java.io.File

/**
 * Android instrumented subclass of [Http3LoopbackTestSuite] — the on-device HTTP/3 loopback parity run
 * (plain GET/POST, dynamic QPACK, server push, the full WebTransport stream matrix, close/drain/reset,
 * middleware). It closes the gap where Android ran only the 4-test `WebTransportTestSuite` while
 * JVM/Linux/Apple ran this comprehensive 27-test suite.
 *
 * Android runs the same quiche backing as the JVM (the in-process [withQuicServer]/[withHttp3Connection]
 * resolve to :socket-quic-quiche), and the quiche JNI `.so` (`libquiche_jni.so`) merges into this test
 * APK transitively from :socket-quic-quiche's `androidMain/jniLibs` — so the loopback links and runs
 * on-device, and the default pass-through [wrapTestBody] is kept (no `UnsatisfiedLinkError` to skip).
 *
 * The only platform glue is cert resolution: quiche loads the TLS chain + key from a **filesystem path**,
 * but on Android the bundled resources live inside the test APK, so we extract `certs/cert.{crt,key}`
 * (from `src/androidInstrumentedTest/resources/certs/`) into the instrumentation cache dir and hand
 * quiche the real paths — mirroring `AndroidWebTransportTest` / :socket-quic-quiche's `AndroidTestCerts`.
 *
 * Runs on a connected device/emulator (`./gradlew :socket-http3:connectedAndroidTest`).
 */
class AndroidHttp3LoopbackTest : Http3LoopbackTestSuite() {
    private val cacheDir: File by lazy {
        InstrumentationRegistry.getInstrumentation().context.cacheDir
    }

    private fun certPath(name: String): String {
        val out = File(cacheDir, name)
        val cl = AndroidHttp3LoopbackTest::class.java.classLoader ?: error("no classloader")
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
}
