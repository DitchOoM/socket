package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.SSLHandshakeFailedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end W3C `serverCertificateHashes` leaf-hash pinning over the real quiche backend (FFM on
 * JDK 21+): a client that pins the server's actual leaf-cert SHA-256 connects with `verify_peer` off
 * (HashOnly — the pin is the sole trust check, browser parity), while a client that pins a wrong hash
 * is rejected post-handshake with [SSLHandshakeFailedException]. Exercises the full path:
 * applyQuicOptions verify_peer policy -> handshake -> QuicheCmd.PeerCert read -> Sha256 -> compare.
 */
class QuicCertificateHashPinningTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /** SHA-256 over the leaf certificate's DER encoding — what `serverCertificateHashes` pins. */
    private fun leafCertSha256(pemPath: String): ByteArray {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = File(pemPath).inputStream().use { cf.generateCertificate(it) }
        return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
    }

    /** Wrap a 32-byte digest into a [CertificateHash] (its value buffer must hold exactly 32 bytes). */
    private fun certHashOf(digest: ByteArray): CertificateHash {
        require(digest.size == 32) { "expected a 32-byte SHA-256, got ${digest.size}" }
        val buf = BufferFactory.Default.allocate(digest.size)
        digest.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }

    @Test
    fun acceptsMatchingLeafHash() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val handlerRan = CompletableDeferred<Unit>()
                        val serverJob = launch(Dispatchers.IO) { connections { handlerRan.complete(Unit) } }
                        try {
                            val pinned = certHashOf(leafCertSha256(certPath("cert.crt")))
                            val clientOptions = testQuicOptions.copy(serverCertificateHashes = listOf(pinned))
                            // No throw == verification passed.
                            withQuicConnection("localhost", port, clientOptions, timeout = 10.seconds) {}
                            withTimeout(10.seconds) { handlerRan.await() }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun rejectsWrongLeafHash() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(15.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        // The handshake completes (verify_peer off), so the server may briefly accept before
                        // the client tears the connection down on the failed pin. Swallow + cancel.
                        val serverJob = launch(Dispatchers.IO) { runCatching { connections {} } }
                        try {
                            val wrong = certHashOf(ByteArray(32) { 0 })
                            val clientOptions = testQuicOptions.copy(serverCertificateHashes = listOf(wrong))
                            val ex =
                                assertFailsWith<SSLHandshakeFailedException> {
                                    withQuicConnection("localhost", port, clientOptions, timeout = 10.seconds) {}
                                }
                            assertTrue(
                                ex.message?.contains("hash", ignoreCase = true) == true,
                                "expected a hash-mismatch message, got: ${ex.message}",
                            )
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }
}
