package com.ditchoom.socket.quic

/**
 * JVM member of [PeerTransportParamsLayoutTestSuite] — DitchOoM/socket#388.
 *
 * **This lane covers two backends, and only one of them was ever broken.** The JVM picks its quiche
 * binding by JDK: JNI on 8–20, FFM on 21+ (`-PquicheJvmBackend=ffm` forces it). FFM computes the
 * struct's byte offsets by hand and was reading `active_conn_id_limit` where the header says the
 * `disable_active_migration` bool is; JNI simply had not bound the accessor at all, so it inherited a
 * benign default and the suite would have passed there while FFM burned. A run of this member on the
 * default backend alone therefore proves nothing about the other — run it **both** ways.
 */
class JvmPeerTransportParamsLayoutTests : PeerTransportParamsLayoutTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmPeerTransportParamsLayoutTests::class, block)
}
