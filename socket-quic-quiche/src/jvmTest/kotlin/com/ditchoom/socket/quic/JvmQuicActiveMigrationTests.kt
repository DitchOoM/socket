package com.ditchoom.socket.quic

/**
 * JVM member of the shared [QuicActiveMigrationTestSuite].
 *
 * The JVM wires a real `UdpSocketChannelFactory` (`CommonJvmWithQuicConnection.kt:211`), so both suite
 * tests are expected to pass. Together with [LinuxQuicActiveMigrationTests] this is the control group:
 * a suite red on every platform would only indict the suite, whereas JVM and Linux green alongside a
 * red [AppleQuicActiveMigrationTests] localises the defect to Apple's missing factory.
 *
 * Distinct from the pre-existing [QuicMigrationLoopbackTests], which migrates to a second loopback
 * address; this member covers the portable fresh-ephemeral-port path shared with every other target.
 *
 * [wrapTestBody] uses the shared `skipOnMissingNativeLib` hook so a build without the JNI/FFM quiche
 * native skips cleanly rather than failing for an unrelated reason.
 */
class JvmQuicActiveMigrationTests : QuicActiveMigrationTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmQuicActiveMigrationTests::class, block)
}
