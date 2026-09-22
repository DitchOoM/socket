@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/** Linux K/Native member of [TrafficSecretsLogTestSuite]: the cinterop binding of `quiche_conn_set_keylog_path`. */
class LinuxTrafficSecretsLogTests : TrafficSecretsLogTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override fun newDirectory(): String = NativeTestFiles.newDirectory("traffic-secrets")

    override fun filesIn(dir: String): Map<String, String> = NativeTestFiles.filesIn(dir)
}
