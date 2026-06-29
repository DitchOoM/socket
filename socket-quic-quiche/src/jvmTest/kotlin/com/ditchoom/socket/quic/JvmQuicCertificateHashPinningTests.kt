package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/**
 * JVM member of the shared [QuicCertificateHashPinningTestSuite]. Provides JVM cert resolution, computes
 * the expected leaf-cert SHA-256 via `java.security` (independent of the impl under test), and maps
 * `UnsatisfiedLinkError` to a skip. Runs against the JNI backend in the Gradle test classpath.
 */
class JvmQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun fixtureTlsConfig(name: String) = QuicTlsConfig(certChainPath = certPath("$name.crt"), privKeyPath = certPath("$name.key"))

    override fun fixtureLeafHash(name: String): CertificateHash {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = File(certPath("$name.crt")).inputStream().use { cf.generateCertificate(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded) // ByteArray — test code
        val buf = BufferFactory.Default.allocate(digest.size)
        digest.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
