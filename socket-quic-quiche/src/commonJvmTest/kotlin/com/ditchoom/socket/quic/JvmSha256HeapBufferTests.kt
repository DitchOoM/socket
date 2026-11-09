package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.managed
import kotlin.test.Test

/**
 * [Sha256KnownAnswerTests]' vectors with heap buffers on both sides. On JVM/Android [sha256Into] reads a heap
 * input through its backing array and writes the digest straight into a heap destination's array; a direct
 * buffer takes the `ByteBuffer` view and `digest()` paths instead, which the shared suite covers.
 */
class JvmSha256HeapBufferTests {
    private val heap = BufferFactory.managed()

    @Test
    fun emptyInput() = Sha256Vectors.empty(heap)

    @Test
    fun abc() = Sha256Vectors.abc(heap)

    @Test
    fun twoBlockInput() = Sha256Vectors.twoBlock(heap)

    @Test
    fun oneHundredTwelveByteInput() = Sha256Vectors.oneHundredTwelve(heap)

    @Test
    fun oneMillionBytes() = Sha256Vectors.oneMillion(heap)

    @Test
    fun sliceWithNonZeroPositionIsHashedAndNotConsumed() = Sha256Vectors.positionedSlice(heap)
}
