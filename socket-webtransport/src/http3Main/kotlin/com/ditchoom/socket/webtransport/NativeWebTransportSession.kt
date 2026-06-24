package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.BytesWritten
import com.ditchoom.buffer.flow.HalfCloseable
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.flow.Resettable
import com.ditchoom.buffer.flow.WritePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import com.ditchoom.socket.http3.WebTransportSession as Http3WebTransportSession
import com.ditchoom.socket.http3.WebTransportStreamException as Http3WebTransportStreamException

/**
 * The jvm/android/native [WebTransportSession]: a **thin adapter** over socket-http3's concrete
 * [Http3WebTransportSession] (Fork 1 = A).
 *
 * Data is zero-copy (socket-http3's stream classes already implement the Phase-3a buffer interfaces
 * this neutral API speaks), but the write-bearing streams are wrapped so the **exception** a peer abort
 * raises is the platform-neutral [WebTransportStreamException] — *not* socket-http3's same-named type,
 * which the browser backend can't reference (it has no socket-http3 on the classpath). That makes the
 * abort exception identical on every backing, so cross-platform code catches one type. The read side
 * already surfaces a peer reset as the shared [ReadResult.Reset], so receive-only streams pass straight
 * through. Session close codes are the same 32-bit value, reinterpreted [Int] (socket-http3) ↔ [UInt]
 * (neutral) at the boundary.
 */
internal class NativeWebTransportSession(
    private val delegate: Http3WebTransportSession,
) : WebTransportSession {
    override val isClosed: Boolean get() = delegate.isClosed

    override val closeInfo: WebTransportCloseInfo?
        get() = delegate.closeInfo?.let { WebTransportCloseInfo(it.code.toUInt(), it.reason) }

    override suspend fun awaitClosed(): WebTransportCloseInfo =
        delegate.awaitClosed().let {
            WebTransportCloseInfo(it.code.toUInt(), it.reason)
        }

    override suspend fun openBidiStream(): ByteStream = NeutralBidiStream(delegate.openBidiStream())

    override suspend fun openUniStream(): ByteSink = NeutralSendStream(delegate.openUniStream())

    override val incomingBidiStreams: Flow<ByteStream> get() = delegate.incomingBidiStreams.map { NeutralBidiStream(it) }

    // Receive-only: a peer reset surfaces as ReadResult.Reset (already neutral), so no wrapping needed.
    override val incomingUniStreams: Flow<ByteSource> get() = delegate.incomingUniStreams

    override suspend fun sendDatagram(payload: ReadBuffer) = delegate.sendDatagram(payload)

    override val datagrams: Flow<ReadBuffer> get() = delegate.datagrams

    override suspend fun close(
        code: UInt,
        reason: String,
    ) = delegate.close(code.toInt(), reason)
}

/** Translate socket-http3's stream-abort exception into the neutral one (same 32-bit code). */
private fun Http3WebTransportStreamException.toNeutral(): WebTransportStreamException =
    WebTransportStreamException(errorCode, message ?: "WebTransport stream aborted by peer", this)

/**
 * Wraps a socket-http3 bidirectional WebTransport stream so [write] raises the neutral
 * [WebTransportStreamException] on a peer abort. Re-exposes [HalfCloseable] + [Resettable] so the public
 * API's `is`-smart-casts still reach half-close / reset. Read is delegated verbatim (its [ReadResult.Reset]
 * is already the neutral peer-reset signal).
 */
private class NeutralBidiStream(
    private val delegate: ByteStream,
) : ByteStream,
    HalfCloseable,
    Resettable {
    override val isOpen: Boolean get() = delegate.isOpen
    override val readPolicy: ReadPolicy get() = delegate.readPolicy
    override val writePolicy: WritePolicy get() = delegate.writePolicy

    override suspend fun read(deadline: Duration): ReadResult = delegate.read(deadline)

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        try {
            delegate.write(buffer, deadline)
        } catch (e: Http3WebTransportStreamException) {
            throw e.toNeutral()
        }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten =
        try {
            delegate.writeGathered(buffers, deadline)
        } catch (e: Http3WebTransportStreamException) {
            throw e.toNeutral()
        }

    override suspend fun shutdownSend() = (delegate as HalfCloseable).shutdownSend()

    override suspend fun reset(errorCode: Long) = (delegate as Resettable).reset(errorCode)

    override suspend fun close() = delegate.close()
}

/**
 * Wraps a socket-http3 outgoing-unidirectional WebTransport stream so [write] raises the neutral
 * [WebTransportStreamException] on a peer STOP_SENDING. Re-exposes [Resettable] for the abort smart-cast.
 */
private class NeutralSendStream(
    private val delegate: ByteSink,
) : ByteSink,
    Resettable {
    override val isOpen: Boolean get() = delegate.isOpen
    override val writePolicy: WritePolicy get() = delegate.writePolicy

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten =
        try {
            delegate.write(buffer, deadline)
        } catch (e: Http3WebTransportStreamException) {
            throw e.toNeutral()
        }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        deadline: Duration,
    ): BytesWritten =
        try {
            delegate.writeGathered(buffers, deadline)
        } catch (e: Http3WebTransportStreamException) {
            throw e.toNeutral()
        }

    override suspend fun reset(errorCode: Long) = (delegate as Resettable).reset(errorCode)

    override suspend fun close() = delegate.close()
}
