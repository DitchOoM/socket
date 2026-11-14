package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.linuxSockAddrLayout
import platform.posix.F_OK
import platform.posix.access

/** Linux Kotlin/Native member of [ConcurrentRequestsTestSuite]. */
class LinuxConcurrentRequestsTests : ConcurrentRequestsTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
            codec = SocketAddressCodec(linuxSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
