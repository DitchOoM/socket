package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/**
 * Apple K/Native member of [MigrationSimTestSuite].
 *
 * Apple is the platform whose migration behaviour is least covered — `migrate()` still reports
 * unsupported there (#374) and the client's UDP rides `NWConnection` — so seeded, virtual-time
 * migration scenarios running against this native is the coverage that did not exist before.
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
        )
}
