package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **The peer's certificate can still be read after the connection is gone.**
 *
 * A client checks the server's certificate (`serverCertificateHashes`, the chain verifiers) after the
 * handshake completes, by reading it from quiche through the driver. A server may close the instant its
 * handshake completes. Its handler returned at once in `QuicCertificateHashPinningTestSuite`, which then
 * failed on the macOS JNI lane: the client's read reached a driver that had already torn down, and threw
 * `ClosedSendChannelException` instead of the typed pinning verdict. A read that was queued and drained
 * at teardown was answered "no certificate" instead.
 *
 * The driver now copies the peer's chain out of quiche as it tears down, and both late paths are answered
 * from that copy. This closes a connection first, then reads, and compares the bytes with the server's
 * certificate file.
 */
class PeerCertificateAfterCloseTests {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val options =
        QuicOptions(
            alpnProtocols = listOf("cert-after-close"),
            verifyPeer = false,
            idleTimeout = 1.minutes,
            migration = MigrationPolicy.Forbidden,
        )

    @Test
    fun theLeafAndChainAreReadableAfterClose() =
        failIfWedged(2.minutes, "peer certificate after close", Dispatchers.IO) {
            skipOnMissingNativeLib(PeerCertificateAfterCloseTests::class) {
                val tls = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
                val server = buildJvmQuicServer(QuicPortBinding.Own(port = 0, host = "127.0.0.1"), tls, options)
                try {
                    val serverJob = launch { server.connections { } }
                    val connection =
                        buildJvmQuicConnection(
                            QuicEndpoint(ResolvedAddress("127.0.0.1", IpFamily.V4), server.port),
                            "localhost",
                            options,
                            TransportConfig(bufferFactory = BufferFactory.deterministic()),
                            20.seconds,
                            loadQuicheApi(),
                        )
                    connection.close()
                    assertTrue(connection.quicheDriver.commands.isClosedForSend, "the connection must be gone before the read")

                    val factory = BufferFactory.deterministic()
                    val der = factory.allocate(16 * 1024)
                    try {
                        val leafLength = connection.readPeerCertDer(der, 16 * 1024)
                        val expected = leafDer()
                        assertEquals(expected.size, leafLength, "the leaf read after close")
                        val bytes = ByteArray(leafLength).also { for (i in it.indices) it[i] = der[i] }
                        assertContentEquals(sha256(expected), sha256(bytes))
                        // The chain read by position as well; index 0 is the leaf.
                        assertEquals(leafLength, connection.readPeerCertChainDer(0, der, 16 * 1024))
                        // Past the end: 0, as quiche answers.
                        assertEquals(0, connection.readPeerCertChainDer(64, der, 16 * 1024))
                    } finally {
                        der.freeNativeMemory()
                    }
                    serverJob.cancel()
                } finally {
                    server.close()
                }
            }
        }

    private fun leafDer(): ByteArray =
        File(certPath("cert.crt")).inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input).encoded
        }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
