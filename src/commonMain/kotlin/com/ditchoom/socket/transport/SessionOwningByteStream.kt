package com.ditchoom.socket.transport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.WritePolicy
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * The **single-stream projection** of a multiplexed transport (RFC_UNIFIED_ESTABLISHMENT.md §3.3):
 * one bidirectional [stream] on a session, bundled with ownership of that session so the
 * transport-agnostic [Transport] surface behaves like TCP — one connection, one byte stream, closing
 * the stream tears the connection down.
 *
 * [close] closes the [stream] and *then* the session (via [closeSession]) — even if the stream close
 * throws — so a dropped byte stream never leaks the underlying QUIC connection / WebTransport session.
 *
 * [mapError] translates transport-specific read/write failures into the unified
 * [com.ditchoom.socket.SocketException] family (RFC §6.1) so a protocol library binding to the agnostic
 * surface catches one error vocabulary regardless of transport. [CancellationException] is never
 * remapped. The projection is intentionally a *plain* [ByteStream] — not [com.ditchoom.buffer.flow.Resettable]
 * or [com.ditchoom.buffer.flow.HalfCloseable] — because those are per-transport powers, not part of the
 * agnostic contract (TCP has neither); reach for them via the Layer-2 session API instead.
 */
class SessionOwningByteStream(
    private val stream: ByteStream,
    private val closeSession: suspend () -> Unit,
    private val mapError: (Throwable) -> Throwable = { it },
) : ByteStream {
    override val isOpen: Boolean get() = stream.isOpen

    override val readPolicy: ReadPolicy get() = stream.readPolicy

    override val writePolicy: WritePolicy get() = stream.writePolicy

    override suspend fun read(deadline: Duration): ReadResult =
        try {
            stream.read(deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw mapError(t)
        }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.write(buffer, deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw mapError(t)
        }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten =
        try {
            stream.writeGathered(buffers, deadline)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw mapError(t)
        }

    override suspend fun close() {
        try {
            stream.close()
        } finally {
            closeSession()
        }
    }
}
