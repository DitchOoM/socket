package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.socket.udp.SocketAddressCodec
import com.ditchoom.socket.udp.hostOsSockAddrLayout
import org.junit.runner.RunWith

/** Android (quiche JNI) member of [FinishedStreamReleaseTestSuite]. */
@RunWith(AndroidJUnit4::class)
class AndroidFinishedStreamReleaseTests : FinishedStreamReleaseTestSuite() {
    override fun simEnv(): MigrationSimEnv =
        MigrationSimEnv(
            api = loadQuicheApi(),
            certChainPath = AndroidTestCerts.tlsConfig.certChainPath,
            privKeyPath = AndroidTestCerts.tlsConfig.privKeyPath,
            codec = SocketAddressCodec(hostOsSockAddrLayout()),
            randomPin = loadQuicheRandomPin(),
        )

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidFinishedStreamReleaseTests::class, block)
}
