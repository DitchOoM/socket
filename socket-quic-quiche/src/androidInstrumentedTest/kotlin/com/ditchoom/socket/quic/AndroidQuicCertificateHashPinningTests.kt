package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory

/**
 * Android (quiche JNI) member of the shared [QuicCertificateHashPinningTestSuite] — the Android
 * counterpart of [JvmQuicCertificateHashPinningTests] / [LinuxQuicCertificateHashPinningTests]. Replaces
 * the former hand-reimplemented copy: extending the shared suite keeps the W3C leaf-hash pinning +
 * certificate-constraint coverage (accept / wrong-hash / expired / too-long / non-P256) in lockstep with
 * the other platforms.
 *
 * [AndroidTestCerts] extracts the bundled fixtures to real file paths; the expected leaf SHA-256 is
 * computed via `java.security` (independent of the impl under test); `UnsatisfiedLinkError` maps to a skip
 * when the quiche JNI isn't present.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    override fun fixtureTlsConfig(name: String) = AndroidTestCerts.tlsConfigFor(name)

    override fun fixtureLeafHash(name: String): CertificateHash {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = File(AndroidTestCerts.path("$name.crt")).inputStream().use { cf.generateCertificate(it) }
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
