package com.ditchoom.socket.quic

/** JVM member of [PmtuPathEventTestSuite]; run it on both backends (JNI by default, `-PquicheJvmBackend=ffm`). */
class JvmPmtuPathEventTests : PmtuPathEventTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmPmtuPathEventTests::class, block)
}
