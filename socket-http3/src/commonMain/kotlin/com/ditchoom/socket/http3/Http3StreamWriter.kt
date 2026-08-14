package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSinkStalledException
import com.ditchoom.buffer.flow.writeFully
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.socket.SocketWriteStalledException
import com.ditchoom.socket.TransportConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place HTTP/3 puts bytes on a stream.
 *
 * Every outbound shape this layer has — a frame, a unidirectional stream-type prefix, a push-stream
 * header, a WebTransport stream prefix, a QPACK encoder/decoder instruction — is the same three steps:
 * encode into a pooled buffer, write it to the stream, free the buffer on every path. That triple was
 * copied across [Http3Connection], [Http3ServerConnection], [Http3ServerResponse], [WebTransportMux]
 * and the loopback test server — `writeFrame` alone existed in four byte-identical copies — so each one
 * was free to be *separately* wrong.
 *
 * They were: all of them issued a single `stream.write(...)` and discarded the returned
 * [BytesWritten][com.ditchoom.buffer.flow.BytesWritten], even where the KDoc promised to "write the
 * whole frame". A [ByteSink][com.ditchoom.buffer.flow.ByteSink] may accept a write only in PART — a
 * QUIC stream does exactly that at a flow-control boundary — and for a length-prefixed protocol the
 * dropped tail is corruption rather than loss: the frame's length varint has already told the peer how
 * many bytes to expect, so it reads on and swallows the frames that FOLLOW as this one's tail, and the
 * stream never re-aligns.
 *
 * Consolidating is what makes that fixable once: [writeFully] is called in exactly two places below,
 * and every HTTP/3 write inherits it. `Http3StreamWriteConsolidationTest` fails the build if a new
 * direct `stream.write(...)` appears in this module.
 *
 * Not thread-safe by itself. The control and QPACK **encoder** streams pass their owner's [Mutex] to
 * the methods that take one; the QPACK **decoder** stream does not appear here, because
 * [QpackDecoderStream] owns its lock together with the accounting ordered against it.
 */
internal class Http3StreamWriter(
    private val pool: BufferPool,
    private val config: TransportConfig,
    /**
     * Which write deadline governs. The connection classes have always imposed [Http3WriteDeadline.CONFIG];
     * [WebTransportMux] deliberately defers to each stream's own policy ([Http3WriteDeadline.LEAF] — the
     * adapter rule, "propagate, don't clobber"). Consolidating the write path must not silently unify the
     * two, so the divergence is a declared parameter rather than an accident of which class you are in.
     */
    private val deadlineSource: Http3WriteDeadline = Http3WriteDeadline.CONFIG,
) {
    /**
     * Every byte this layer sends passes through here — the single [writeFully] seam.
     *
     * Being the one seam is also what makes the error mapping a single line: `writeFully` raises
     * buffer's [ByteSinkStalledException], an `IllegalStateException`, and letting that escape would put
     * it outside the [SocketException][com.ditchoom.socket.SocketException] family every other error
     * this layer raises belongs to — on JVM, outside `catch (e: IOException)` too.
     */
    private suspend fun ByteSink.emit(buffer: ReadBuffer) =
        try {
            when (deadlineSource) {
                Http3WriteDeadline.CONFIG -> writeFully(buffer, config.writePolicy.toDeadline())
                Http3WriteDeadline.LEAF -> writeFully(buffer)
            }
        } catch (e: ByteSinkStalledException) {
            throw SocketWriteStalledException(e)
        }

    /** Encode [frame] into a pooled buffer and write the whole frame to [stream]. */
    suspend fun writeFrame(
        stream: ByteSink,
        frame: Http3Frame,
    ) {
        // The generated framed encode owns allocation (slicing scheme over the pool) and returns a
        // ReadBuffer spanning exactly the frame's wire bytes.
        val buffer = Http3FrameCodec.encode(frame, EncodeContext.Empty, pool)
        try {
            stream.emit(buffer)
        } finally {
            // Free on both paths: write is zero-copy and does not take ownership.
            buffer.freeIfNeeded()
        }
    }

    /** [writeFrame] under [mutex] — for a stream whose writes must not interleave. */
    suspend fun writeFrame(
        stream: ByteSink,
        mutex: Mutex,
        frame: Http3Frame,
    ) = mutex.withLock { writeFrame(stream, frame) }

    /**
     * Write [values] as consecutive QUIC variable-length integers — the shape behind every prefix this
     * layer emits: a bare stream-type prefix (RFC 9114 §6.2), a push-stream header (§4.6, type + push
     * id), and a WebTransport stream prefix (type + session id).
     */
    suspend fun writeVarInts(
        stream: ByteSink,
        vararg values: Long,
    ) {
        var capacity = 0
        for (value in values) capacity += VarIntCodec.encodedLength(value)
        val buffer = pool.allocate(capacity)
        try {
            for (value in values) VarIntCodec.encode(buffer, value, EncodeContext.Empty)
            buffer.resetForRead()
            stream.emit(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /** Encode and write one QPACK encoder-stream instruction (RFC 9204 §4.3) under [mutex]. */
    suspend fun writeEncoderInstruction(
        stream: ByteSink,
        mutex: Mutex,
        instruction: QpackEncoderInstruction,
    ) {
        val capacity =
            when (instruction) {
                is QpackEncoderInstruction.InsertWithNameRef -> INSTRUCTION_OVERHEAD + qpackUtf8ByteLength(instruction.value)
                is QpackEncoderInstruction.InsertWithLiteralName ->
                    INSTRUCTION_OVERHEAD + qpackUtf8ByteLength(instruction.name) + qpackUtf8ByteLength(instruction.value)
                // SetCapacity / Duplicate: a single prefixed integer
                else -> INSTRUCTION_OVERHEAD
            }
        val buffer = pool.allocate(capacity)
        try {
            QpackEncoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            mutex.withLock { stream.emit(buffer) }
        } finally {
            buffer.freeIfNeeded()
        }
    }

    /**
     * Encode and write one QPACK decoder-stream instruction (RFC 9204 §4.4).
     *
     * No `mutex` parameter, unlike its encoder-stream sibling: serialization for this stream belongs to
     * [QpackDecoderStream], which holds the lock *and* the acknowledgment accounting that must be
     * ordered with the write. Taking a lock here as well would be the second lock on one stream that
     * owner exists to remove.
     */
    suspend fun writeDecoderInstruction(
        stream: ByteSink,
        instruction: QpackDecoderInstruction,
    ) {
        val buffer = pool.allocate(DECODER_INSTRUCTION_CAPACITY)
        try {
            QpackDecoderInstructionCodec.encode(buffer, instruction)
            buffer.resetForRead()
            stream.emit(buffer)
        } finally {
            buffer.freeIfNeeded()
        }
    }

    private companion object {
        /** Headroom for an instruction's prefixed integers, on top of any string payload. */
        const val INSTRUCTION_OVERHEAD = 32

        /** A decoder instruction is a single prefixed integer (≤ ~9 bytes). */
        const val DECODER_INSTRUCTION_CAPACITY = 16
    }
}

/** Which deadline governs an [Http3StreamWriter]'s writes. See its `deadlineSource` parameter. */
internal enum class Http3WriteDeadline {
    /** Impose `TransportConfig.writePolicy` — what the HTTP/3 connection classes do. */
    CONFIG,

    /** Defer to each leaf stream's own `writePolicy` — the adapter rule, used by the WebTransport mux. */
    LEAF,
}
