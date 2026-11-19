package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A read asks quiche for the stream's read chunk, whatever size the buffer factory's buffer is: a pooled factory hands
 * back a larger buffer than asked for whenever one is idle, and the read must not take that as the size to ask.
 */
class CoreFirstReadSizeTests {
    @Test
    fun aFirstReadWithEightyKibibytesQueuedAsksForTheInitialChunkEvenFromAnOversizedBuffer() =
        runBlocking {
            skipOnMissingNativeLib(CoreFirstReadSizeTests::class) {
                withEchoServer { port ->
                    val client = connectCore(port, factory = Oversized(BufferFactory.network()))
                    val stream = client.connection.openStream()
                    val payload = BufferFactory.network().allocate(PAYLOAD)
                    repeat(PAYLOAD) { payload.writeByte(it.toByte()) }
                    payload.resetForRead()
                    stream.writeFully(payload, 10.seconds)
                    // Everything is echoed back and queued on the stream before the first read starts.
                    delay(1.seconds)
                    val first = stream.read(10.seconds)
                    if (first !is ReadResult.Data) fail("expected data: $first")
                    val got = first.buffer.remaining()
                    first.buffer.freeIfNeeded()
                    client.connection.close(QuicError.NoError)
                    assertTrue(got <= INITIAL_CHUNK, "the first read asked for $INITIAL_CHUNK bytes at most but got $got")
                }
            }
        }

    /** Every buffer is [BIG] bytes, as a pool with an idle larger buffer returns it. */
    private class Oversized(
        private val real: BufferFactory,
    ) : BufferFactory by real {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = real.allocate(maxOf(size, BIG), byteOrder)
    }

    private companion object {
        const val PAYLOAD = 80 * 1024
        const val INITIAL_CHUNK = 2_048
        const val BIG = 65_536
    }
}
