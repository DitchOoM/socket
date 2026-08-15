package com.ditchoom.socket.quic

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * JVM malformed-packet fuzz test — the JVM member of the shared [QuicMalformedPacketTestSuite]. Provides
 * JVM cert resolution, the shared `skipOnMissingNativeLib` hook, and a plain [DatagramChannel]
 * sender for the raw datagrams.
 */
class QuicMalformedPacketTests : QuicMalformedPacketTestSuite() {
    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(QuicMalformedPacketTests::class, block)

    override suspend fun sendRawDatagram(
        port: Int,
        bytes: ByteArray,
    ) {
        DatagramChannel.open().use { channel ->
            channel.send(ByteBuffer.wrap(bytes), InetSocketAddress("127.0.0.1", port))
        }
    }
}
