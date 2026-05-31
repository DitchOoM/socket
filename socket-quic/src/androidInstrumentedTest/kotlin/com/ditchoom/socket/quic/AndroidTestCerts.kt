package com.ditchoom.socket.quic

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Shared support for the in-process Android QUIC server-role tests
 * ([AndroidQuicLoopbackTests], [AndroidQuicPassiveMigrationTests]).
 *
 * quiche loads the TLS cert chain + private key from a **filesystem path**
 * ([QuicTlsConfig.certChainPath] / [QuicTlsConfig.privKeyPath]) — it can't read a
 * classpath URL the way the JVM tests do via `File(url.toURI())`, because on Android
 * the bundled resources live inside the test APK, not on disk. So we extract the
 * bundled `certs/cert.crt` + `certs/cert.key` (from
 * `src/androidInstrumentedTest/resources/certs/`) into the instrumentation cache dir
 * once and hand quiche the real paths.
 */
internal object AndroidTestCerts {
    private val cacheDir: File by lazy {
        InstrumentationRegistry.getInstrumentation().context.cacheDir
    }

    /** Extract bundled `certs/[name]` to a real file and return its absolute path. */
    fun path(name: String): String {
        val out = File(cacheDir, name)
        val cl = AndroidTestCerts::class.java.classLoader ?: error("no classloader")
        val resource = "certs/$name"
        val input =
            cl.getResourceAsStream(resource)
                ?: error("Bundled test cert not found on classpath: $resource")
        input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
        return out.absolutePath
    }

    val tlsConfig: QuicTlsConfig
        get() = QuicTlsConfig(certChainPath = path("cert.crt"), privKeyPath = path("cert.key"))
}
