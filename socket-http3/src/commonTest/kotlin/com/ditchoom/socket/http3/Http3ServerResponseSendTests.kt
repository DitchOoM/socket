package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.FinishingWrite
import com.ditchoom.socket.quic.QuicByteStream
import com.ditchoom.socket.quic.QuicStreamId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How many sending calls a buffered response costs its stream, and which of them carries the FIN. */
class Http3ServerResponseSendTests {
    private sealed interface Sent {
        data class Bytes(
            val count: Int,
        ) : Sent

        data class BytesAndFin(
            val count: Int,
        ) : Sent

        data object Fin : Sent
    }

    private class RecordingStream :
        ByteStream,
        FinishingWrite {
        val sends = mutableListOf<Sent>()
        override val isOpen: Boolean = true
        override val readPolicy: ReadPolicy = ReadPolicy.Bounded(15.seconds)
        override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)

        override suspend fun read(deadline: Duration): ReadResult = ReadResult.End

        override suspend fun write(
            buffer: ReadBuffer,
            deadline: Duration,
        ): BytesWritten {
            val n = buffer.remaining()
            buffer.position(buffer.position() + n)
            sends += Sent.Bytes(n)
            return BytesWritten(n)
        }

        override suspend fun writeFullyAndShutdownSend(
            buffer: ReadBuffer,
            deadline: Duration,
        ) {
            val n = buffer.remaining()
            buffer.position(buffer.position() + n)
            sends += Sent.BytesAndFin(n)
        }

        override suspend fun shutdownSend() {
            sends += Sent.Fin
        }

        override suspend fun close() = Unit
    }

    private fun response(stream: RecordingStream): Http3ServerResponse {
        val id = QuicStreamId(0)
        return Http3ServerResponse(
            QuicByteStream(id, stream),
            BufferPool(factory = BufferFactory.Default),
            TransportConfig(),
            id,
        ) { fields, _ ->
            val size = (QpackFieldSectionCodec.wireSize(fields, EncodeContext.Empty) as WireSize.Exact).bytes
            BufferFactory.Default.allocate(size).also {
                QpackFieldSectionCodec.encode(it, fields, EncodeContext.Empty)
                it.resetForRead()
            }
        }
    }

    @Test
    fun aResponseWithoutABodyIsOneWriteThatAlsoEndsTheStream() =
        runTest {
            val stream = RecordingStream()
            val response = response(stream)

            response.send(204)
            response.finish()

            assertEquals(1, stream.sends.size, "sending calls for a response without a body: ${stream.sends}")
            assertIs<Sent.BytesAndFin>(stream.sends.single())
        }

    @Test
    fun aResponseWithABodyEndsTheStreamWithItsDataFrame() =
        runTest {
            val stream = RecordingStream()
            val response = response(stream)
            val body = BufferFactory.Default.allocate(5)
            repeat(5) { body.writeByte(7) }
            body.resetForRead()

            response.send(200, body = body)
            response.finish()

            assertEquals(2, stream.sends.size, "sending calls for a response with a body: ${stream.sends}")
            assertIs<Sent.Bytes>(stream.sends[0])
            // The DATA frame: type, length, then the five body bytes.
            assertEquals(Sent.BytesAndFin(2 + 5), stream.sends[1])
        }

    @Test
    fun aStreamedResponseStillEndsWithAFinOfItsOwn() =
        runTest {
            val stream = RecordingStream()
            val response = response(stream)

            response.sendHeaders(200)
            response.finish()

            assertEquals(listOf(Sent.Bytes::class, Sent.Fin::class), stream.sends.map { it::class })
        }
}
