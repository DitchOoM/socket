package com.ditchoom.socket.quic.replay

import com.ditchoom.socket.quic.AppleTestCerts
import com.ditchoom.socket.quic.CinteropQuicheApi
import com.ditchoom.socket.quic.MigrationSimEnv
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.appleSockAddrLayout

/** Apple K/Native member of [CaptureReplayTestSuite], against Apple's quiche cinterop build. */
class AppleCaptureReplayTests : CaptureReplayTestSuite() {
    override fun simEnv(certificate: TestCertificate): MigrationSimEnv =
        MigrationSimEnv(
            api = CinteropQuicheApi,
            certChainPath =
                when (certificate) {
                    TestCertificate.Rsa -> AppleTestCerts.tlsConfig.certChainPath
                    TestCertificate.Ecdsa -> AppleTestCerts.requireGenerated("pinned.crt")
                },
            privKeyPath =
                when (certificate) {
                    TestCertificate.Rsa -> AppleTestCerts.tlsConfig.privKeyPath
                    TestCertificate.Ecdsa -> AppleTestCerts.requireGenerated("pinned.key")
                },
            codec = SocketAddressCodec(appleSockAddrLayout),
            randomPin = CinteropQuicheApi,
        )

    /** A simulator's working directory holds no build-generated certificate: a replay that needs one is skipped there. */
    override suspend fun wrapTestBody(block: suspend () -> Unit) = AppleTestCerts.skippingWhenSimulatorLacksFixtures(block)
}
