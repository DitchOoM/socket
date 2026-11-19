package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A native `size_t` written into a buffer leaves the buffer's byte order as it was. The buffer goes back to a pool
 * shared with the core, whose next owner writes connection ids big-endian through it: a buffer left in native order
 * wrote them reversed, and a server's first reply named an id its route table did not hold.
 */
class NativeSizeTByteOrderTests {
    @Test
    fun aSizeTWrittenAndReadLeavesTheBufferBigEndian() {
        val buffer = BufferFactory.Default.allocate(16)
        writeNativeSizeT(buffer, 20)
        assertEquals(20, readNativeSizeT(buffer))
        buffer.resetForWrite()
        buffer.writeLong(0x0102030405060708L)
        assertEquals(0x01.toByte(), buffer[0], "the next owner's writes are still big-endian")
    }
}
