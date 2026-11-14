package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.AppleTestCerts
import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/** Apple K/Native member of [CaptureReplayTestSuite], against Apple's quiche cinterop build. */
class AppleCaptureReplayTests : CaptureReplayTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = AppleTestCerts.tlsConfig.certChainPath,
            privKeyPath = AppleTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(appleSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
