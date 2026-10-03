@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

/** Linux K/N member of [ServerCertVerifierTestSuite]. */
class LinuxServerCertVerifierTests : ServerCertVerifierTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun twoCertificateChainTlsConfig() =
        QuicTlsConfig(certChainPath = certPath("two-cert-chain.crt"), privKeyPath = certPath("cert.key"))

    override fun twoCertificateChainPem(): String =
        memScoped {
            val path = certPath("two-cert-chain.crt")
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
}
