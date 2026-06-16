package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SHA-256 conformance against NIST FIPS 180-4 test vectors, plus the buffer-contract guarantees the
 * leaf-certificate-hash verifier relies on (input not consumed, repeatable).
 */
class Sha256Tests {
    private fun sha256Hex(input: ByteArray): String {
        val inBuf = BufferFactory.Default.allocate(maxOf(1, input.size))
        if (input.isNotEmpty()) inBuf.writeBytes(input)
        inBuf.resetForRead()
        val out = BufferFactory.Default.allocate(Sha256.DIGEST_SIZE)
        Sha256.digest(inBuf, out)
        out.resetForRead()
        val digest = out.readByteArray(Sha256.DIGEST_SIZE)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    @Test
    fun emptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(ByteArray(0)),
        )
    }

    @Test
    fun abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".encodeToByteArray()),
        )
    }

    @Test
    fun twoBlockMessage() {
        // 56 bytes — 56 + 1 + 8 = 65 > 64, so padding spills into a second block (boundary case).
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
    }

    @Test
    fun exactBlockBoundary() {
        // 64 bytes — a full block whose padding forms an entire second block.
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            sha256Hex(ByteArray(64) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun doesNotConsumeInput() {
        val bytes = "abc".encodeToByteArray()
        val inBuf = BufferFactory.Default.allocate(bytes.size)
        inBuf.writeBytes(bytes)
        inBuf.resetForRead()
        val positionBefore = inBuf.position()
        val remainingBefore = inBuf.remaining()

        val out = BufferFactory.Default.allocate(Sha256.DIGEST_SIZE)
        Sha256.digest(inBuf, out)

        assertEquals(positionBefore, inBuf.position(), "digest must not advance the input position")
        assertEquals(remainingBefore, inBuf.remaining(), "digest must not consume the input")
    }
}
