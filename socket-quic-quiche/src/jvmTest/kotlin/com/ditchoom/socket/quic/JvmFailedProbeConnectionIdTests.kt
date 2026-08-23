package com.ditchoom.socket.quic

import java.io.File

/**
 * JVM member of [FailedProbeConnectionIdTestSuite] — the lane #447's fix was mutation-proven on.
 *
 * Runs on whichever backend the build selected, so the FFM lane exercises the retirement through the
 * `java.lang.foreign` binding and the default lane through JNI.
 */
class JvmFailedProbeConnectionIdTests : FailedProbeConnectionIdTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    internal override suspend fun buildServer(
        binding: QuicPortBinding,
        tlsConfig: QuicTlsConfig,
        options: QuicOptions,
    ): SharedQuicheServer = buildJvmQuicServer(binding, tlsConfig, options)

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmFailedProbeConnectionIdTests::class, block)
}
