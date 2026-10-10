package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/**
 * Apple K/Native member of [MigrationSimTestSuite].
 *
 * Runs the seeded, virtual-time migration scenarios against Apple's quiche cinterop build. The
 * real-socket counterpart, where `migrate()` opens a second `NWConnection`, is
 * [AppleQuicActiveMigrationTests].
 *
 * Fixtures come from [AppleTestCerts], which resolves the simulator lanes' absolute export as well as
 * the macOS checkout (#359); a genuinely missing pair fails loudly rather than skipping silently.
 */
class AppleMigrationSimTests : MigrationSimTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath = AppleTestCerts.tlsConfig.certChainPath,
            privKeyPath = AppleTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(appleSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )
}
