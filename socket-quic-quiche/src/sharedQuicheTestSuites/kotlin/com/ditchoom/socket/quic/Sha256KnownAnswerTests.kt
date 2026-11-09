package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FIPS 180-4 known answers for [sha256Into], the digest behind W3C `serverCertificateHashes` pinning.
 *
 * Every backend binds its own digest (JCA `MessageDigest` on JVM/Android, the already-linked BoringSSL on
 * Linux and Apple), so this suite lives where every backend runs it, Android's instrumented lane included.
 * A wrong digest here is silent in production: a pin that never matches looks exactly like a server whose
 * certificate changed.
 *
 * Inputs use [BufferFactory.network], as production does (the verifier's DER buffer is native so quiche
 * gets an address): the native actuals read through a raw address and reject anything else.
 * [BufferFactory.Default] is NOT native on Linux. [JvmSha256HeapBufferTests] repeats the vectors with heap
 * buffers on the JVM, where the actual reads the backing array instead.
 */
class Sha256KnownAnswerTests {
    @Test
    fun emptyInput() = Sha256Vectors.empty(BufferFactory.network())

    @Test
    fun abc() = Sha256Vectors.abc(BufferFactory.network())

    @Test
    fun twoBlockInput() = Sha256Vectors.twoBlock(BufferFactory.network())

    @Test
    fun oneHundredTwelveByteInput() = Sha256Vectors.oneHundredTwelve(BufferFactory.network())

    @Test
    fun oneMillionBytes() = Sha256Vectors.oneMillion(BufferFactory.network())

    @Test
    fun sliceWithNonZeroPositionIsHashedAndNotConsumed() = Sha256Vectors.positionedSlice(BufferFactory.network())
}

/** The vectors, shared with the JVM heap-buffer variant. Expected values are FIPS 180-4 / NIST examples. */
internal object Sha256Vectors {
    private const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private const val ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private const val TWO_BLOCK_INPUT = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
    private const val TWO_BLOCK = "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    private const val INPUT_112 =
        "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
    private const val DIGEST_112 = "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1"
    private const val ONE_MILLION_A = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"

    fun empty(factory: BufferFactory) = assertEquals(EMPTY, digestHex(ascii(factory, ""), factory))

    fun abc(factory: BufferFactory) = assertEquals(ABC, digestHex(ascii(factory, "abc"), factory))

    fun twoBlock(factory: BufferFactory) = assertEquals(TWO_BLOCK, digestHex(ascii(factory, TWO_BLOCK_INPUT), factory))

    fun oneHundredTwelve(factory: BufferFactory) = assertEquals(DIGEST_112, digestHex(ascii(factory, INPUT_112), factory))

    fun oneMillion(factory: BufferFactory) {
        val input = factory.allocate(1_000_000)
        repeat(1_000_000) { input.writeByte('a'.code.toByte()) }
        input.resetForRead()
        assertEquals(ONE_MILLION_A, digestHex(input, factory))
    }

    /**
     * "abc" framed by bytes that must not be hashed: position 2, limit 5. The digest must be "abc"'s, the
     * input's position and limit must be untouched (the non-consuming contract), and the digest must land
     * at the destination's own position, not at zero.
     */
    fun positionedSlice(factory: BufferFactory) {
        val input = ascii(factory, "XXabcYYY")
        input.position(2)
        input.setLimit(5)
        val dest = factory.allocate(4 + 32 + 4)
        repeat(4) { dest.writeByte(0x55) }
        sha256Into(input, dest)
        assertEquals(2, input.position(), "input position must not move")
        assertEquals(5, input.limit(), "input limit must not move")
        assertEquals(4 + 32, dest.position(), "dest advances by exactly 32")
        dest.resetForRead()
        repeat(4) { assertEquals(0x55, dest.readByte().toInt(), "bytes before dest's position are untouched") }
        assertEquals(ABC, hex(dest, 32))
    }

    private fun ascii(
        factory: BufferFactory,
        text: String,
    ): PlatformBuffer {
        val buffer = factory.allocate(maxOf(text.length, 1))
        for (c in text) buffer.writeByte(c.code.toByte())
        buffer.resetForRead()
        return buffer
    }

    private fun digestHex(
        input: PlatformBuffer,
        factory: BufferFactory,
    ): String {
        val position = input.position()
        val limit = input.limit()
        val dest = factory.allocate(32)
        sha256Into(input, dest)
        assertEquals(position, input.position(), "input position must not move")
        assertEquals(limit, input.limit(), "input limit must not move")
        assertEquals(32, dest.position())
        dest.resetForRead()
        return hex(dest, 32)
    }

    private fun hex(
        buffer: PlatformBuffer,
        count: Int,
    ): String =
        buildString {
            repeat(count) {
                val b = buffer.readByte().toInt() and 0xff
                append("0123456789abcdef"[b ushr 4])
                append("0123456789abcdef"[b and 0xf])
            }
        }
}
