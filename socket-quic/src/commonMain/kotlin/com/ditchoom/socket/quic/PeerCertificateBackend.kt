package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * The platform half of [PeerCertificate] generation, supplied by a [QuicEngine] through
 * [PeerCertificateSupport.Available]: key generation, signing, SHA-256, and handing PEM material to the
 * engine's TLS stack. Everything that makes the result a valid W3C `serverCertificateHashes` certificate
 * (the X.509 encoding, the P-256 algorithm identifiers, the validity bound) is common code and cannot be
 * changed by a backend.
 *
 * Every method reports failure by throwing [PeerCertificateException].
 */
interface PeerCertificateBackend {
    /** A fresh ECDSA key pair on NIST P-256 (secp256r1), held in memory only. */
    fun generateKeyPair(): P256KeyPair

    /**
     * Write the SHA-256 of [input]'s remaining bytes (32 bytes) into [dest] at its position, advancing it.
     * Does not consume [input].
     */
    fun sha256(
        input: ReadBuffer,
        dest: WriteBuffer,
    )

    /**
     * Write [certificatePem] and [privateKeyPem] to files only the current user can read, run [block]
     * with their paths, and delete both files when [block] returns or throws. Does not consume either
     * buffer.
     */
    suspend fun <R> withPemFiles(
        certificatePem: ReadBuffer,
        privateKeyPem: ReadBuffer,
        block: suspend (QuicTlsConfig) -> R,
    ): R
}

/** An in-memory ECDSA P-256 key pair produced by [PeerCertificateBackend.generateKeyPair]. */
interface P256KeyPair : AutoCloseable {
    /** Write the SEC 1 uncompressed public point `04 ‖ X ‖ Y` (65 bytes) into [dest], advancing it. */
    fun writePublicKey(dest: WriteBuffer)

    /** Write the private scalar as 32 big-endian bytes into [dest], advancing it. */
    fun writePrivateKey(dest: WriteBuffer)

    /**
     * Sign [message]'s remaining bytes with ECDSA over SHA-256 and write the DER `ECDSA-Sig-Value`
     * (`SEQUENCE { r INTEGER, s INTEGER }`, at most [P256_MAX_SIGNATURE_BYTES]) into [dest], advancing
     * it. Does not consume [message].
     */
    fun sign(
        message: ReadBuffer,
        dest: WriteBuffer,
    )

    /** Release the key; the pair is unusable afterwards. */
    override fun close()
}

/** Largest DER `ECDSA-Sig-Value` a P-256 signature encodes to. */
const val P256_MAX_SIGNATURE_BYTES: Int = 72
