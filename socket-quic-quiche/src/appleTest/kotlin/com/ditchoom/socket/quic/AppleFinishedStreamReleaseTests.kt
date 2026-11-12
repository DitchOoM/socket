package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/** Apple Kotlin/Native member of [FinishedStreamReleaseTestSuite]. */
class AppleFinishedStreamReleaseTests : FinishedStreamReleaseTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = AppleTestCerts.tlsConfig.certChainPath,
            privKeyPath = AppleTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(appleSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
