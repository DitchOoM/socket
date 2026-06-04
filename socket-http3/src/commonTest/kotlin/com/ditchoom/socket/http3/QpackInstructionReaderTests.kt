package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** [QpackInstructionReader] reassembles instructions across arbitrary read boundaries (1 byte at a time). */
class QpackInstructionReaderTests {
    /** A ByteStream that replays [bytes] one byte per read() — the worst case for instruction splitting. */
    private class DripStream(
        private val bytes: List<Int>,
    ) : ByteStream {
        private var i = 0
        override val isOpen get() = i < bytes.size

        override suspend fun read(timeout: Duration): ReadResult {
            if (i >= bytes.size) return ReadResult.End
            val buf = BufferFactory.Default.allocate(1)
            buf.writeByte(bytes[i++].toByte())
            buf.resetForRead()
            return ReadResult.Data(buf)
        }

        override suspend fun write(
            buffer: ReadBuffer,
            timeout: Duration,
        ): BytesWritten = BytesWritten(0)

        override suspend fun close() {}
    }

    private fun encodedBytes(instructions: List<QpackEncoderInstruction>): List<Int> {
        val buf = BufferFactory.Default.allocate(512)
        instructions.forEach { QpackEncoderInstructionCodec.encode(buf, it) }
        buf.resetForRead()
        return (0 until buf.remaining()).map { buf.readByte().toInt() and 0xFF }
    }

    @Test
    fun reassemblesEncoderInstructionsSplitAcrossReads() =
        runTest {
            val instructions =
                listOf(
                    QpackEncoderInstruction.SetCapacity(4096),
                    QpackEncoderInstruction.InsertWithLiteralName("x-long-header-name", "a-long-header-value-here"),
                    QpackEncoderInstruction.InsertWithNameRef(0, isStatic = true, value = "example.com"),
                    QpackEncoderInstruction.Duplicate(0),
                )
            val pool = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.Default)
            val reader = QpackInstructionReader.encoder(DripStream(encodedBytes(instructions)), pool)

            val decoded =
                buildList {
                    while (true) {
                        add(reader.next() ?: break)
                    }
                }
            assertEquals(instructions, decoded)
        }

    @Test
    fun cleanEndReturnsNull() =
        runTest {
            val pool = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.Default)
            val reader = QpackInstructionReader.decoder(DripStream(emptyList()), pool)
            assertEquals(null, reader.next())
        }
}
