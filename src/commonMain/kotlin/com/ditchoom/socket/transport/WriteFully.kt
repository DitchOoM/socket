package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.socket.SocketWriteStalledException
import kotlin.time.Duration

/**
 * Write **every** byte of [buffer] to this sink, resuming until it is drained.
 *
 * [ByteSink.write] returns [BytesWritten][com.ditchoom.buffer.flow.BytesWritten] because a write may be
 * PARTIAL — the contract calls the post-write position "the resume point for a partial write's
 * residue". A caller that issues one `write` and discards the count therefore has a latent truncation
 * bug: it looks correct against every sink that happens to accept everything, and silently loses the
 * tail against one that does not.
 *
 * Most sinks in this repo never expose it — the NIO sockets loop internally until the buffer is gone,
 * `MemoryTransport` copies wholesale, and the browser WebTransport writer takes the whole chunk. A QUIC
 * stream does: quiche's `stream_send` buffers only as many bytes as the stream's flow-control credit
 * allows at that moment and reports the count. A fully blocked stream parks on its writable signal and
 * retries inside the driver, but a PARTIALLY open one returns early by design, which is the normal
 * state at a window boundary and most reliable on a fresh stream before the peer has extended credit.
 *
 * **For a length-prefixed protocol a short write is corruption, not loss.** The length header the peer
 * has already read still declares the full size, so the peer keeps reading and consumes the frames that
 * FOLLOW as this frame's tail: the truncated frame arrives at exactly the right length with a
 * neighbouring frame's bytes inside it, the swallowed frames never arrive, and the stream never
 * re-aligns. Every framed writer on top of a byte stream — MQTT over [CodecConnection], HTTP/3 frames,
 * QPACK instructions, WebTransport stream prefixes — needs this, which is why it is one shared helper
 * rather than a loop per call site.
 *
 * Resumption is driven by the reported COUNT rather than by the cursor, so both sink shapes work: the
 * position is re-derived after every call, which neither double-advances a contract-compliant sink nor
 * strands one that leaves the cursor alone (the quiche stream hands the native address to quiche and
 * does not move it).
 *
 * Returns [Unit] deliberately: there is no residue left for a caller to reason about, and no count to
 * accidentally ignore. Use [ByteSink.write] directly only when implementing a sink that must faithfully
 * report a partial count to ITS caller.
 *
 * @throws SocketWriteStalledException if the sink reports no progress while bytes are still pending.
 *   That is a broken sink rather than back-pressure — back-pressure blocks inside `write` — so it fails
 *   loudly instead of spinning, and instead of returning early, which is the truncation this prevents.
 */
suspend fun ByteSink.writeFully(
    buffer: ReadBuffer,
    deadline: Duration,
) {
    while (buffer.remaining() > 0) {
        val before = buffer.position()
        val written = write(buffer, deadline).count
        if (written <= 0) {
            throw SocketWriteStalledException(accepted = written, pending = buffer.remaining())
        }
        val resumeAt = before + written
        if (buffer.position() != resumeAt) buffer.position(resumeAt)
    }
}

/**
 * [writeFully] using the sink's injected [writePolicy][ByteSink.writePolicy] — the adapter rule
 * ("propagate, don't clobber"): the leaf owns the deadline policy for its direction, so prefer this
 * overload unless the caller genuinely has its own budget to impose. The policy bounds each underlying
 * call, as it always has, not the loop as a whole.
 */
suspend fun ByteSink.writeFully(buffer: ReadBuffer) = writeFully(buffer, writePolicy.toDeadline())
