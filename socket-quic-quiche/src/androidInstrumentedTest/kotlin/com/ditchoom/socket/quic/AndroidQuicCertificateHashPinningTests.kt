package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.SSLHandshakeFailedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * W3C `serverCertificateHashes` leaf-hash pinning on the Android/JNI runtime — the Android port of the
 * shared [QuicCertificateHashPinningTestSuite] (`androidInstrumentedTest` can't see `commonTest`, so this
 * is a self-contained parallel copy). Exercises [JniQuicheApi.connPeerCert] end-to-end on-device: an
 * in-process [withQuicServer] presents the bundled leaf cert, and the client pins (or mis-pins) its hash.
 */
@RunWith(AndroidJUnit4::class)
class AndroidQuicCertificateHashPinningTests {
    private val testQuicOptions =
        QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    private val tlsConfig get() = AndroidTestCerts.tlsConfig

    /** SHA-256 of the server's leaf-cert DER, computed via `java.security` (independent of the verifier). */
    private fun expectedLeafCertHash(): CertificateHash {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = File(AndroidTestCerts.path("cert.crt")).inputStream().use { cf.generateCertificate(it) }
        return certHashOf(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
    }

    private fun certHashOf(bytes: ByteArray): CertificateHash {
        val buf = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    @Test
    fun acceptsMatchingLeafHash() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val handlerRan = CompletableDeferred<Unit>()
                        val serverJob = launch(Dispatchers.IO) { connections { handlerRan.complete(Unit) } }
                        try {
                            val pinned = testQuicOptions.copy(serverCertificateHashes = listOf(expectedLeafCertHash()))
                            withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds) {}
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
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { runCatching { connections {} } }
                        try {
                            val pinned = testQuicOptions.copy(serverCertificateHashes = listOf(certHashOf(ByteArray(32) { 0 })))
                            val ex =
                                assertFailsWith<SSLHandshakeFailedException> {
                                    withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds) {}
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

    private suspend fun skipOnMissingNativeLib(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            assumeTrue("Native lib not available: ${e.message}", false)
        }
    }
}
