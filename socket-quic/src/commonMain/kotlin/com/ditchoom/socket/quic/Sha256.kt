package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Computes the SHA-256 (FIPS 180-4) digest of this buffer's remaining bytes (position..limit) and
 * appends the [Sha256.DIGEST_SIZE] result bytes to [out]. Reads positionally; does **not** consume
 * this buffer (its position is unchanged on return).
 *
 * TEMPORARY HOME — this mirrors the exact signature planned for `com.ditchoom:buffer`'s `ReadBuffer`
 * (as a default interface method). When buffer ships it, delete this extension and the [Sha256]
 * object below: every call site (`buffer.sha256(out)`) is unchanged — the buffer member just resolves
 * instead. Living here for now is what lets both `socket-quic` (Apple backend) and `socket-quic-quiche`
 * (quiche backend) share one digest without exposing the impl, since both already depend on buffer.
 */
fun ReadBuffer.sha256(out: WriteBuffer): Unit = Sha256.digest(this, out)

/**
 * Minimal SHA-256 (FIPS 180-4) over a [ReadBuffer]'s remaining bytes — the digest behind the
 * `serverCertificateHashes` leaf-certificate-hash verifier ([CertificateHash]).
 *
 * Pure Kotlin on purpose: every native backend (JVM/Android quiche, Apple Network.framework, Linux
 * quiche) shares this one implementation. JVM has `java.security.MessageDigest` and Apple has
 * CommonCrypto, but Linux/Kotlin-Native has neither and the statically-linked BoringSSL is not exposed
 * through cinterop — a single common digest avoids per-platform divergence in security-critical code.
 *
 * Not a hot path (one leaf certificate per connection), so it favors clarity over micro-optimization and
 * holds no `ByteArray` (state is `IntArray`; input/output are buffers).
 */
internal object Sha256 {
    /** SHA-256 digest length in bytes. */
    const val DIGEST_SIZE = 32

    private const val BLOCK_SIZE = 64

    // First 32 bits of the fractional parts of the cube roots of the first 64 primes (FIPS 180-4 §4.2.2).
    private val K =
        intArrayOf(
            0x428a2f98,
            0x71374491,
            -0x4a3f0431,
            -0x164a245b,
            0x3956c25b,
            0x59f111f1,
            -0x6dc07d5c,
            -0x54e3a12b,
            -0x27f85568,
            0x12835b01,
            0x243185be,
            0x550c7dc3,
            0x72be5d74,
            -0x7f214e02,
            -0x6423f959,
            -0x3e640e8c,
            -0x1b64963f,
            -0x1041b87a,
            0x0fc19dc6,
            0x240ca1cc,
            0x2de92c6f,
            0x4a7484aa,
            0x5cb0a9dc,
            0x76f988da,
            -0x67c1aeae,
            -0x57ce3993,
            -0x4ffcd838,
            -0x40a68039,
            -0x391ff40d,
            -0x2a586eb9,
            0x06ca6351,
            0x14292967,
            0x27b70a85,
            0x2e1b2138,
            0x4d2c6dfc,
            0x53380d13,
            0x650a7354,
            0x766a0abb,
            -0x7e3d36d2,
            -0x6d8dd37b,
            -0x5d40175f,
            -0x57e599b5,
            -0x3db47490,
            -0x3893ae5d,
            -0x2e6d17e7,
            -0x2966f9dc,
            -0x0bf1ca7b,
            0x106aa070,
            0x19a4c116,
            0x1e376c08,
            0x2748774c,
            0x34b0bcb5,
            0x391c0cb3,
            0x4ed8aa4a,
            0x5b9cca4f,
            0x682e6ff3,
            0x748f82ee,
            0x78a5636f,
            -0x7b3787ec,
            -0x7338fdf8,
            -0x6f410006,
            -0x5baf9315,
            -0x41065c09,
            -0x398e870e,
        )

    private fun rotr(
        x: Int,
        n: Int,
    ): Int = (x ushr n) or (x shl (32 - n))

    /**
     * Compute the SHA-256 digest of [input]'s bytes from its current position to its limit and append the
     * [DIGEST_SIZE] result bytes to [out]. Reads [input] positionally (`get(index)`), so it does **not**
     * consume the buffer — its position is unchanged on return.
     */
    fun digest(
        input: ReadBuffer,
        out: WriteBuffer,
    ) {
        val msgLen = input.remaining()
        val start = input.position()
        val bitLen = msgLen.toLong() * 8

        // Padded length: message + 0x80 + zero fill + 8-byte big-endian bit length, rounded up to a
        // multiple of the 64-byte block size.
        val totalLen = ((msgLen + 1 + 8 + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE

        // Byte at index [j] of the padded message, synthesized so no padded copy is materialized.
        fun byteAt(j: Int): Int =
            when {
                j < msgLen -> input[start + j].toInt() and 0xFF
                j == msgLen -> 0x80
                j < totalLen - 8 -> 0x00
                else -> {
                    val shift = (totalLen - 1 - j) * 8
                    ((bitLen ushr shift) and 0xFF).toInt()
                }
            }

        // Initial hash values: fractional parts of the square roots of the first 8 primes (FIPS 180-4 §5.3.3).
        val h =
            intArrayOf(
                0x6a09e667,
                -0x4498517b,
                0x3c6ef372,
                -0x5ab00ac6,
                0x510e527f,
                -0x64fa9774,
                0x1f83d9ab,
                0x5be0cd19,
            )
        val w = IntArray(64)

        var blockStart = 0
        while (blockStart < totalLen) {
            for (t in 0 until 16) {
                val b = blockStart + t * 4
                w[t] =
                    (byteAt(b) shl 24) or (byteAt(b + 1) shl 16) or (byteAt(b + 2) shl 8) or byteAt(b + 3)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            for (t in 0 until 64) {
                val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + bigS1 + ch + K[t] + w[t]
                val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = bigS0 + maj
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
            blockStart += BLOCK_SIZE
        }

        for (i in 0 until 8) {
            out.writeByte((h[i] ushr 24).toByte())
            out.writeByte((h[i] ushr 16).toByte())
            out.writeByte((h[i] ushr 8).toByte())
            out.writeByte(h[i].toByte())
        }
    }
}
