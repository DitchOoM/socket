package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.linuxSockAddrLayout
import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [MigrationSimTestSuite].
 *
 * Until this existed, every migration scenario in the suite ran on one backend. Linux is where the
 * field failures were captured — the public echo server the device rig drives is a Linux host — so
 * running the seeded scenarios against this native is the point of the whole promotion.
 *
 * cinterop fixes the binding at compile time, so there is no missing-native skip path.
 */
class LinuxMigrationSimTests : MigrationSimTestSuite() {
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
        )
}
