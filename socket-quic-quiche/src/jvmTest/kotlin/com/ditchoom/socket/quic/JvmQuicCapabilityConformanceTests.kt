package com.ditchoom.socket.quic

/**
 * JVM member of [QuicCapabilityConformanceTestSuite].
 *
 * The JVM declares [LocalEndpointSupport.Bindable] on the strength of NIO binding the requested local
 * endpoint before connecting (`CommonJvmWithQuicConnection.kt`). This is the lane that makes that claim
 * *earned*: the suite asks the real factory for a named port and reads back the port the socket bound.
 *
 * The same declaration serves **Android**, which shares `commonJvmMain` verbatim — so a flip there
 * shows up here too, and [AndroidQuicCapabilityConformanceTests] additionally covers it on the device's
 * own `UdpSocket` actual over the JNI native.
 */
class JvmQuicCapabilityConformanceTests : QuicCapabilityConformanceTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmQuicCapabilityConformanceTests::class, block)
}
