package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.CertificateHashPinningException
import com.ditchoom.socket.CertificateHashPinningFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Shared **W3C `serverCertificateHashes` leaf-hash pinning** test suite (Phase 4, Option 1). Exercises
 * the full quiche-backend verifier end-to-end against a real loopback server: handshake (with
 * `verify_peer` off under the default [CertificateHashVerification.HashOnly]), then the post-handshake
 * `quiche_conn_peer_cert` read → SHA-256 → compare.
 *
 * Same 3-tier shape as the other suites: this commonMain abstract drives the test; per-platform
 * subclasses supply [testTlsConfig] and [expectedLeafCertHash] (the SHA-256 of that cert's leaf DER —
 * computed independently of the impl under test: JVM via `java.security`, Linux a hardcoded value from
 * `openssl ... | openssl dgst -sha256`). Running on both JVM (FFM/JNI) and Linux (cinterop) validates
 * each backend's [QuicheApi.connPeerCert] at runtime.
 */
abstract class QuicCertificateHashPinningTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** The expected pin: SHA-256 of [testTlsConfig]'s leaf certificate DER, as a [CertificateHash]. */
    abstract fun expectedLeafCertHash(): CertificateHash

    /** Platform hook for skip-on-missing-native-lib (JVM converts `UnsatisfiedLinkError` to a skip). */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    private fun options() = QuicOptions(alpnProtocols = listOf("test"), verifyPeer = false, idleTimeout = 10.seconds)

    /** A pin that does NOT match the server's leaf (32 zero bytes) — must be rejected. */
    private fun wrongHash(): CertificateHash {
        val buf = BufferFactory.deterministic().allocate(32)
        repeat(32) { buf.writeByte(0) }
        buf.resetForRead()
        return CertificateHash(buf)
    }

    /** Pinning the server's actual leaf-cert hash lets the connection establish (the pin is the trust check). */
    @Test
    fun acceptsMatchingLeafHash() =
        runQuicTest {
            wrapTestBody {
                val opts = options()
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val handlerRan = CompletableDeferred<Unit>()
                    val serverJob = launch { connections { handlerRan.complete(Unit) } }
                    try {
                        val pinned = opts.copy(serverCertificateHashes = listOf(expectedLeafCertHash()))
                        // No throw == the leaf-hash pin verified.
                        withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds.scaled) {}
                        handlerRan.await()
                    } finally {
                        serverJob.cancel()
                    }
                }
            }
        }

    /**
     * Pinning a non-matching hash must reject the connection with a [CertificateHashPinningException]
     * whose [CertificateHashPinningFailure.HashMismatch] carries the leaf the server actually presented.
     * Every backend throws the identical type (quiche post-handshake; Apple/NW from the verify_block),
     * and `HashMismatch` (vs `NoPeerCertificate`) also proves the leaf DER was read at runtime.
     */
    @Test
    fun rejectsWrongLeafHash() =
        runQuicTest {
            wrapTestBody {
                val opts = options()
                withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = opts) {
                    val serverJob = launch { runCatching { connections {} } }
                    try {
                        val pinned = opts.copy(serverCertificateHashes = listOf(wrongHash()))
                        val ex =
                            assertFailsWith<CertificateHashPinningException> {
                                withQuicConnection("127.0.0.1", port, pinned, timeout = 10.seconds.scaled) {}
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
