package com.ditchoom.socket.quic

/** JVM run of the shared [QuicServerLifecycleTestSuite]: every black-box lifecycle invariant of the base suite. */
class JvmQuicServerLifecycleTestSuite : QuicServerLifecycleTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmQuicServerLifecycleTestSuite::class, block)
}
