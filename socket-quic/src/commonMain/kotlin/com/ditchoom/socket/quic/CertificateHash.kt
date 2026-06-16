package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer

/**
 * A pinned server **leaf**-certificate hash for `serverCertificateHashes`-style verification (W3C
 * WebTransport): the peer's TLS leaf certificate is accepted iff the hash of its DER encoding equals one
 * of the pinned values.
 *
 * Distinct from [QuicOptions.trustedCaCertificatesPem], which pins *CA anchors* and still walks a chain.
 * This pins the *leaf itself*, so it can authenticate a self-signed or short-lived certificate with no
 * CA at all — see [QuicOptions.certificateHashVerification] for how it combines with chain validation.
 *
 * @property value the raw hash bytes. For `"sha-256"` this is exactly 32 bytes; the buffer is read from
 *   its current [ReadBuffer.position] to its limit at verification time and is not consumed.
 * @property algorithm the hash algorithm. Only `"sha-256"` is defined by the WebTransport spec and
 *   currently supported.
 */
class CertificateHash(
    val value: ReadBuffer,
    val algorithm: String = "sha-256",
) {
    init {
        require(algorithm == "sha-256") {
            "Unsupported certificate hash algorithm '$algorithm' (only \"sha-256\")"
        }
        require(value.remaining() == SHA256_BYTES) {
            "sha-256 certificate hash must be $SHA256_BYTES bytes, got ${value.remaining()}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertificateHash) return false
        return algorithm == other.algorithm && value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        // Content-based, consistent with equals (ReadBuffer has no contentHashCode). Positional reads
        // (get(index)) over the remaining bytes — does not consume the buffer. Cheap for a 32-byte hash.
        var result = algorithm.hashCode()
        val end = value.position() + value.remaining()
        var i = value.position()
        while (i < end) {
            result = 31 * result + value[i].toInt()
            i++
        }
        return result
    }

    override fun toString(): String = "CertificateHash($algorithm, ${value.remaining()}B)"

    private companion object {
        const val SHA256_BYTES = 32
    }
}

/**
 * How [QuicOptions.serverCertificateHashes] combines with ordinary certificate-chain validation.
 * Ignored when [QuicOptions.serverCertificateHashes] is empty.
 */
enum class CertificateHashVerification {
    /**
     * Default — browser parity (W3C WebTransport). The leaf-hash match is the **sole** trust check;
     * chain validation is skipped. This is what the browser does for `serverCertificateHashes`, and it
     * is what makes the canonical WebTransport case (a self-signed / short-lived leaf with no CA) work
     * identically on native and in the browser. Pinning the exact leaf *is* the trust decision.
     */
    HashOnly,

    /**
     * Stricter, native-only opt-in. A pinned leaf-hash match is required **and** the chain must still
     * validate (system trust, or [QuicOptions.trustedCaCertificatesPem] when set) — defense in depth for
     * a leaf that genuinely chains to a CA. NOTE: a self-signed / unchained leaf is rejected under this
     * mode even with a matching hash (the browser cannot express this mode at all).
     */
    RequireBoth,
}
