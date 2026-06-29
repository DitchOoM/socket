package com.ditchoom.socket.transport

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Prototype slice for the v6 "propagate, don't clobber" adapter rule.
 *
 * A WebTransport stream is persistent: its [ReadPolicy] is [ReadPolicy.UntilClosed]. In v5,
 * `CodecConnection.fillFromTransport()` injected `options.readTimeout` (15s) on every transport
 * read — silently defeating that policy and timing out a stream that should never time out.
 *
 * In v6 the deadline lives on the stream as an injected `val readPolicy`, and `CodecConnection`
 * calls the leaf's **no-arg** `read()`, which consults that policy. This test pins the fix by
 * recording the deadline the leaf actually receives: `UntilClosed` must arrive as
 * [Duration.INFINITE], never as a clobbering 15s.
 */
private class PolicyProbeStream(
    override val readPolicy: ReadPolicy,
    private val frames: ArrayDeque<ReadBuffer>,
) : ByteStream {
    override val writePolicy: WritePolicy = WritePolicy.Bounded(15.seconds)
    override val isOpen: Boolean get() = frames.isNotEmpty()

    /** The deadline the most recent [read] call was given — the thing the adapter rule governs. */
    var lastReadDeadline: Duration? = null
        private set

    override suspend fun read(deadline: Duration): ReadResult {
        lastReadDeadline = deadline
        return if (frames.isEmpty()) ReadResult.End else ReadResult.Data(frames.removeFirst())
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = BytesWritten(buffer.remaining())

    override suspend fun close() {}
}

class WebTransportPolicyPrototypeTests {
    private fun frame(value: String): ReadBuffer {
        val buf = BufferFactory.Default.allocate(2 + value.length)
        TestStringCodec.encode(buf, value, EncodeContext.Empty)
        buf.resetForRead()
        return buf
    }

    @Test
    fun untilClosedPolicyPropagatesInfiniteDeadlineThroughCodecConnection() =
        runTest {
            val stream = PolicyProbeStream(ReadPolicy.UntilClosed, ArrayDeque(listOf(frame("live"))))
            val conn = CodecConnection(stream, TestStringCodec, TransportConfig())

            val message = conn.receive().first()
            assertEquals("live", message)

            // The footgun fix: the leaf's UntilClosed policy reached read() as INFINITE — the
            // CodecConnection did NOT inject a 15s deadline of its own.
            assertEquals(Duration.INFINITE, stream.lastReadDeadline)
        }

    @Test
    fun boundedPolicyPropagatesItsOwnDeadline() =
        runTest {
            val stream = PolicyProbeStream(ReadPolicy.Bounded(7.seconds), ArrayDeque(listOf(frame("req"))))
            val conn = CodecConnection(stream, TestStringCodec, TransportConfig())

            val message = conn.receive().first()
            assertEquals("req", message)

            // A request/response stream keeps its Bounded deadline — also propagated, not clobbered.
            assertEquals(7.seconds, stream.lastReadDeadline)
        }
}
