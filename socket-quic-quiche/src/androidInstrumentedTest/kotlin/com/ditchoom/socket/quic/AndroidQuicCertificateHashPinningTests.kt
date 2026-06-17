package com.ditchoom.socket.quic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
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

    // The W3C-compliant accept fixture (EC P-256, 13-day); the violators drive the reject tests.
    private val tlsConfig get() = AndroidTestCerts.tlsConfigFor("pinned")

    /** SHA-256 of a named fixture's leaf-cert DER, computed via `java.security` (independent of the verifier). */
    private fun leafHashFor(name: String): CertificateHash {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = File(AndroidTestCerts.path("$name.crt")).inputStream().use { cf.generateCertificate(it) }
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
                            val pinned = testQuicOptions.copy(serverCertificateHashes = listOf(leafHashFor("pinned")))
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
                                assertFailsWith<CertificateHashPinningException> {
                                    withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds) {}
                                }
                            val failure = ex.failure
                            assertTrue(
                                failure is CertificateHashPinningFailure.HashMismatch,
                                "expected HashMismatch, got: $failure",
                            )
                            assertTrue(
                                failure.computedLeafHash.startsWith("sha-256:"),
                                "expected an algorithm-prefixed computed hash, got: ${failure.computedLeafHash}",
                            )
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /** A pinned leaf whose hash matches but whose validity window is in the past — NotTemporallyValid. */
    @Test
    fun rejectsExpiredLeaf() =
        runConstraintRejectTest("pinned-expired") { failure ->
            assertTrue(failure is CertificateHashPinningFailure.NotTemporallyValid, "expected NotTemporallyValid, got: $failure")
        }

    /** A pinned leaf whose validity period exceeds 14 days — ValidityPeriodTooLong. */
    @Test
    fun rejectsOverlyLongValidityLeaf() =
        runConstraintRejectTest("pinned-toolong") { failure ->
            assertTrue(failure is CertificateHashPinningFailure.ValidityPeriodTooLong, "expected ValidityPeriodTooLong, got: $failure")
        }

    /** A pinned leaf with a non-ECDSA-P-256 (RSA) key — UnsupportedPublicKey. */
    @Test
    fun rejectsNonP256KeyLeaf() =
        runConstraintRejectTest("pinned-rsa") { failure ->
            assertTrue(failure is CertificateHashPinningFailure.UnsupportedPublicKey, "expected UnsupportedPublicKey, got: $failure")
        }

    /**
     * Drive a constraint-violation fixture end-to-end: the server presents [fixture], the client pins that
     * leaf's real hash (so the hash matches and the W3C constraint check — java.security parser + shared
     * policy — is what rejects), and [assertFailure] checks the structured failure. Android is always an
     * Enforced platform, so these run on-device.
     */
    private fun runConstraintRejectTest(
        fixture: String,
        assertFailure: (CertificateHashPinningFailure) -> Unit,
    ) = runBlocking(Dispatchers.IO) {
        skipOnMissingNativeLib {
            withTimeout(20.seconds) {
                withQuicServer(port = 0, tlsConfig = AndroidTestCerts.tlsConfigFor(fixture), quicOptions = testQuicOptions) {
                    val serverJob = launch(Dispatchers.IO) { runCatching { connections {} } }
                    try {
                        val pinned = testQuicOptions.copy(serverCertificateHashes = listOf(leafHashFor(fixture)))
                        val ex =
                            assertFailsWith<CertificateHashPinningException> {
                                withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds) {}
                            }
                        assertFailure(ex.failure)
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
