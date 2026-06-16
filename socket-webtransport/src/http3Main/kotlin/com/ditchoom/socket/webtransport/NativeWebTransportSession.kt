package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.flow.ByteSink
import com.ditchoom.buffer.flow.ByteSource
import com.ditchoom.buffer.flow.ByteStream
import kotlinx.coroutines.flow.Flow
import com.ditchoom.socket.http3.WebTransportSession as Http3WebTransportSession

/**
 * The jvm/android/native [WebTransportSession]: a **thin, zero-copy adapter** over socket-http3's
 * concrete [Http3WebTransportSession] (Fork 1 = A).
 *
 * Every method delegates directly. The streams pass *straight through* with no per-stream wrapping —
 * socket-http3's stream classes already implement the Phase-3a buffer interfaces this neutral API
 * speaks (`WebTransportStream` is a `ByteStream`, `WebTransportSendStream` a `ByteSink`,
 * `WebTransportReceiveStream` a `ByteSource`), and `Flow` is covariant, so the incoming-stream flows
 * need no `map`. The adapter exists only to keep the socket-http3 type out of the public API; it adds
 * no allocation on any hot path.
 */
internal class NativeWebTransportSession(
    private val delegate: Http3WebTransportSession,
) : WebTransportSession {
    override val isClosed: Boolean get() = delegate.isClosed

    override val closeInfo: WebTransportCloseInfo?
        get() = delegate.closeInfo?.let { WebTransportCloseInfo(it.code, it.reason) }

    override suspend fun awaitClosed(): WebTransportCloseInfo = delegate.awaitClosed().let { WebTransportCloseInfo(it.code, it.reason) }

    override suspend fun openBidiStream(): ByteStream = delegate.openBidiStream()

    override suspend fun openUniStream(): ByteSink = delegate.openUniStream()

    override val incomingBidiStreams: Flow<ByteStream> get() = delegate.incomingBidiStreams

    override val incomingUniStreams: Flow<ByteSource> get() = delegate.incomingUniStreams

    override suspend fun sendDatagram(payload: ReadBuffer) = delegate.sendDatagram(payload)

    override val datagrams: Flow<ReadBuffer> get() = delegate.datagrams

    override suspend fun close(
        code: Int,
        reason: String,
    ) = delegate.close(code, reason)
}
