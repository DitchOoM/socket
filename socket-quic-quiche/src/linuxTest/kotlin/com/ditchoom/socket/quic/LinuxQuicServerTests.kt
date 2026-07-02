@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

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

class LinuxQuicServerTests : QuicServerTestSuite() {
    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-quic-quiche/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    /** Read a (text) cert file into a String via posix — test-only, no buffer-lib dependency needed. */
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

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override fun localhostTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("localhost.crt"),
            privKeyPath = certPath("localhost.key"),
        )

    override fun localhostCertPem() = readFileText(certPath("localhost.crt"))

    override fun unrelatedCaPem() = readFileText(certPath("cert.crt"))

    // Native targets compile quiche via cinterop — wrapTestBody stays
    // default (pass-through), no UnsatisfiedLinkError skip needed.
}
