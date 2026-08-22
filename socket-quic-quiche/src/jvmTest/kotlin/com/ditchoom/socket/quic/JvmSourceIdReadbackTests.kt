package com.ditchoom.socket.quic

import java.io.File

/** JVM member of [SourceIdReadbackTestSuite] — covers whichever backend the build selected (JNI or FFM). */
class JvmSourceIdReadbackTests : SourceIdReadbackTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmSourceIdReadbackTests::class, block)
}
