package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.linuxSockAddrLayout
import platform.posix.F_OK
import platform.posix.access

/** Linux K/Native member of [CaptureReplayTestSuite], against Linux's quiche cinterop build. */
class LinuxCaptureReplayTests : CaptureReplayTestSuite() {
    /** [name] under the module's `testcerts`, from the module directory or from the repository root. */
    private fun certPath(name: String): String =
        listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
            .filter { access(it, F_OK) == 0 }
            .take(1)
            .fold("testcerts/$name") { _, found -> found }

    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
            codec = SocketAddressCodec(linuxSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
