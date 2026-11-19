package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/** Apple K/Native member of [StreamsBlockTestSuite]: the cinterop binding of `qnet_streams.h`. */
class AppleStreamsBlockTests : StreamsBlockTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = AppleTestCerts.tlsConfig.certChainPath,
            privKeyPath = AppleTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(appleSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
