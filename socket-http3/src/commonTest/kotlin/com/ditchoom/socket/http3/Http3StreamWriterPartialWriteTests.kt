package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [Http3StreamWriter] must put every byte of a frame on the wire even when the sink accepts a write
 * only in part — which a QUIC stream does at a flow-control boundary.
 *
 * A truncated HTTP/3 frame is corruption rather than loss: the frame's length varint has already told
 * the peer how many bytes to expect, so the peer reads on and consumes the frames that FOLLOW as this
 * one's tail. These tests pin the consolidated writer, which every HTTP/3 write path now shares.
 */
class Http3StreamWriterPartialWriteTests {
    /** Accepts at most [acceptPerWrite] bytes per call and reports the count, as quiche does. */
    private class PartialAcceptSink(
        private val acceptPerWrite: Int,
    ) : ByteSink {
        val wire = ArrayList<Byte>()
        var writeCalls = 0
            private set

        override val isOpen: Boolean get() = true
        override val writePolicy: WritePolicy = WritePolicy.Bounded(1.seconds)

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            writeCalls++
            val take = minOf(acceptPerWrite, buffer.remaining())
            for (i in 0 until take) wire += buffer.readByte()
            return BytesWritten(take)
        }

        override suspend fun close() = Unit
    }

    private fun writer() = Http3StreamWriter(BufferPool(factory = BufferFactory.Default), TransportConfig())

    private fun dataFrameOf(size: Int): Http3Frame.Data {
        val payload = BufferFactory.Default.allocate(size)
        payload.writeBytes(ByteArray(size) { (it % 251).toByte() })
        payload.resetForRead()
        return Http3Frame.Data(payload)
    }

    /** The wire size of the same frame, measured through a sink that accepts everything. */
    private suspend fun encodedSizeOf(frame: Http3Frame): Int {
        val sink = PartialAcceptSink(acceptPerWrite = Int.MAX_VALUE)
        writer().writeFrame(sink, frame)
        return sink.wire.size
    }

    @Test
    fun aDataFrameSurvivesASinkThatAcceptsPartialWrites() =
        runTest {
            val expected = encodedSizeOf(dataFrameOf(32_768))
            val sink = PartialAcceptSink(acceptPerWrite = 1_400)

            writer().writeFrame(sink, dataFrameOf(32_768))

            assertEquals(
                expected,
                sink.wire.size,
                "the whole frame must reach the wire; a truncated one leaves its length varint " +
                    "over-declared and the peer swallows the following frames",
            )
            assertTrue(sink.writeCalls > 1, "the sink was never asked to resume — the test proves nothing")
        }

    /** Consecutive frames must stay aligned: byte-for-byte identical to the unbroken encoding. */
    @Test
    fun consecutiveFramesStayAlignedWhenWritesArePartial() =
        runTest {
            val whole = PartialAcceptSink(acceptPerWrite = Int.MAX_VALUE)
            val partial = PartialAcceptSink(acceptPerWrite = 900)
            val sizes = listOf(9_000, 120, 4_500)

            writer().let { w -> sizes.forEach { w.writeFrame(whole, dataFrameOf(it)) } }
            writer().let { w -> sizes.forEach { w.writeFrame(partial, dataFrameOf(it)) } }

            assertEquals(whole.wire, partial.wire, "a partial-accepting sink must receive identical bytes")
        }

    /** The varint prefixes (stream types, push headers, WT prefixes) go through the same seam. */
    @Test
    fun varIntPrefixesAreWrittenInFullOneByteAtATime() =
        runTest {
            val whole = PartialAcceptSink(acceptPerWrite = Int.MAX_VALUE)
            val partial = PartialAcceptSink(acceptPerWrite = 1)

            writer().writeVarInts(whole, Http3StreamType.PUSH, 1_234_567L)
            writer().writeVarInts(partial, Http3StreamType.PUSH, 1_234_567L)

            assertEquals(whole.wire, partial.wire)
            assertTrue(whole.wire.isNotEmpty())
        }

    /** QPACK instruction writes share the seam too, mutex and all. */
    @Test
    fun qpackEncoderInstructionsAreWrittenInFull() =
        runTest {
            val instruction = QpackEncoderInstruction.SetCapacity(4_096)
            val whole = PartialAcceptSink(acceptPerWrite = Int.MAX_VALUE)
            val partial = PartialAcceptSink(acceptPerWrite = 1)

            writer().writeEncoderInstruction(whole, Mutex(), instruction)
            writer().writeEncoderInstruction(partial, Mutex(), instruction)

            assertEquals(whole.wire, partial.wire)
            assertTrue(whole.wire.isNotEmpty())
        }
}
