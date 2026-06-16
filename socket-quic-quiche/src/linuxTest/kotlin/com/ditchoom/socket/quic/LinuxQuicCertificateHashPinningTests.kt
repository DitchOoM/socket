@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

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

    /** Read a (text) fixture file via posix — test-only, no buffer-lib dependency needed. */
    private fun readFileText(path: String): String =
        memScoped {
            val fp = fopen(path, "r") ?: error("Cannot open $path")
            try {
                val sb = StringBuilder()
                val bufSize = 4096
                val buf = allocArray<ByteVar>(bufSize)
                while (true) {
                    val n = fread(buf, 1.convert(), (bufSize - 1).convert(), fp).toInt()
                    if (n <= 0) break
                    buf[n] = 0
                    sb.append(buf.toKString())
                }
                sb.toString()
            } finally {
                fclose(fp)
            }
        }

    override fun fixtureTlsConfig(name: String) =
        QuicTlsConfig(certChainPath = certPath("$name.crt"), privKeyPath = certPath("$name.key"))

    override fun fixtureLeafHash(name: String): CertificateHash {
        // The build writes `<name>.sha256` (lowercase hex of the leaf DER, computed by java.security —
        // an impl independent of the verifier under test) alongside the cert, so the K/N test reads the
        // expected pin from disk rather than hard-coding a hash that drifts every regeneration.
        val bytes = hexToBytes(readFileText(certPath("$name.sha256")).trim())
        val buf = BufferFactory.deterministic().allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    // Linux enforces the W3C constraints once its BoringSSL-cinterop parser lands (step 3); until then it
    // verifies the leaf hash only, so the constraint-reject tests skip.
    override fun enforcesW3cConstraints() = false

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
        }
}
