package com.ditchoom.socket.quic

import org.junit.Assume.assumeTrue

/**
 * JVM network-impairment test — the JVM member of the shared [QuicImpairmentTestSuite].
 *
 * Provides JVM cert resolution (classpath `certs/`), the `UnsatisfiedLinkError → assumeTrue` skip (so a
 * missing JNI/FFM quiche native skips cleanly), and the shared [DatagramChannelImpairingProxy] (in
 * `sharedJvmTestProtocol`, also used by the Android member). The test bodies live in the common suite,
 * guaranteeing parity with the Linux K/N port.
 */
class QuicImpairmentTests : QuicImpairmentTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    override fun createImpairingProxy(
        serverPort: Int,
        policy: ImpairmentPolicy,
    ): ImpairingProxy = DatagramChannelImpairingProxy(serverPort, policy)
}
