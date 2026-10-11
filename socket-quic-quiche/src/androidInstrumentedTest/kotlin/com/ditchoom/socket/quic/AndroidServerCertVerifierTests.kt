package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.io.File

/** Android member of [ServerCertVerifierTestSuite]. */
@RunWith(AndroidJUnit4::class)
class AndroidServerCertVerifierTests : ServerCertVerifierTestSuite() {
    override fun twoCertificateChainTlsConfig() =
        QuicTlsConfig(certChainPath = AndroidTestCerts.path("two-cert-chain.crt"), privKeyPath = AndroidTestCerts.path("cert.key"))

    override fun twoCertificateChainPem(): String = File(AndroidTestCerts.path("two-cert-chain.crt")).readText()

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(AndroidServerCertVerifierTests::class, block)
}
