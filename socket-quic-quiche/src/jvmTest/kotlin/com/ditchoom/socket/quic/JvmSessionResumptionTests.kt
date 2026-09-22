package com.ditchoom.socket.quic

/** JVM member of [SessionResumptionTestSuite]: JNI by default, FFM with `-PquicheJvmBackend=ffm` — run both. */
class JvmSessionResumptionTests : SessionResumptionTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmSessionResumptionTests::class, block)
}
