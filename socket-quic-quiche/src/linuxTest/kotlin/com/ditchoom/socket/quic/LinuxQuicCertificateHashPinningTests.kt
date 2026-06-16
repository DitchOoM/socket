@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import platform.posix.F_OK
import platform.posix.access

/**
 * Kotlin/Native (linuxX64) member of the shared [QuicCertificateHashPinningTestSuite] — validates the
 * cinterop [CinteropQuicheApi.connPeerCert] verifier at runtime. Native compiles quiche via cinterop, so
 * there is no `UnsatisfiedLinkError` skip path.
 */
class LinuxQuicCertificateHashPinningTests : QuicCertificateHashPinningTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun expectedLeafCertHash(): CertificateHash {
        // SHA-256 of testcerts/cert.crt's leaf DER — a stable fixture (identical across dev + aliens),
        // computed independently of the impl under test via:
        //   openssl x509 -in cert.crt -outform DER | openssl dgst -sha256
        val bytes = hexToBytes("3e7b7dd003758ae1d66932d3a3ee57d24b1113f35ff63f915ddff6e83a0ad209")
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }
}
