@file:OptIn(ExperimentalWasmJsInterop::class)

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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The browser WebTransport streams (wasmJs), bridging WHATWG `ReadableStream`/`WritableStream` onto the
 * Phase-3a byte trichotomy. Identical in shape to the js streams; the only platform difference is the
 * `Uint8Array` ↔ native-buffer boundary, which is a copy on wasm (see [toJsUint8Array] /
 * [uint8ArrayToReadBuffer]) rather than the zero-copy view js can use.
 *
 * These implement **buffer-flow's** [Resettable]/[HalfCloseable] (the cross-platform capability
 * interfaces), so common code reaches reset / half-close by the exact same `is`-smart-cast it uses on
 * the native streams.
 */
private val DEFAULT_WRITE_POLICY = WritePolicy.Bounded(15.seconds)

/** Whether [e] is a `WebTransportError` with `source === "stream"` and a numeric `streamErrorCode`. */
private fun jsHasStreamErrorCode(e: JsAny): Boolean = js("e.source === 'stream' && typeof e.streamErrorCode === 'number'")

/** The `streamErrorCode` of [e]; only meaningful when [jsHasStreamErrorCode] holds. */
private fun jsStreamErrorCode(e: JsAny): Double = js("e.streamErrorCode")

/** Whether [e] is a `DOMException` named `NetworkError` (Chrome's code-less remote stream abort). */
private fun jsIsNetworkErrorDomException(e: JsAny): Boolean =
    js("typeof DOMException === 'function' && e instanceof DOMException && e.name === 'NetworkError'")

/**
 * A caught JS promise rejection that **preserves** the original JS reason [value]. kotlinx-coroutines'
 * wasmJs `Promise.await()` collapses a non-Kotlin rejection (e.g. a `WebTransportError`) into a generic
 * `Exception` carrying only a string message — which would drop the `streamErrorCode` we need for
 * cross-backend parity. So the stream awaits route through [awaitOrThrowRejection], which preserves the
 * reason here intact for [classifyStreamRejection] to inspect.
 */
private class WebTransportJsRejection(
    val value: JsAny?,
) : Exception()

/**
 * Await [this]. On fulfilment resume with the value; on rejection throw a [WebTransportJsRejection]
 * carrying the JS reason verbatim (unlike `await()`, which discards it — see [WebTransportJsRejection]).
 * Modeled on coroutines' own `Promise.await`, only differing in how the rejection is surfaced.
 */
private suspend fun <T : JsAny?> Promise<T>.awaitOrThrowRejection(): T =
    suspendCancellableCoroutine { cont ->
        then(
            { value ->
                cont.resumeWith(Result.success(value))
                null
            },
            { reason ->
                cont.resumeWith(Result.failure(WebTransportJsRejection(reason)))
                null
            },
        )
    }

/**
 * Classify a stream read/write rejection [e] — the inbound counterpart to [webTransportResetReason]. See
 * [BrowserStreamRejection] for the rules.
 */
private fun classifyStreamRejection(e: Throwable): BrowserStreamRejection {
    val reason = (e as? WebTransportJsRejection)?.value ?: return BrowserStreamRejection.NotPeerAbort
    return when {
        jsHasStreamErrorCode(reason) ->
            BrowserStreamRejection.PeerAborted(jsStreamErrorCode(reason).toLong().toUInt())
        jsIsNetworkErrorDomException(reason) -> BrowserStreamRejection.PeerAbortedWithoutCode
        else -> BrowserStreamRejection.NotPeerAbort
    }
}

private suspend fun readChunk(
    reader: ReadableStreamDefaultReaderJs,
    deadline: Duration,
): ReadResult {
    val chunk =
        try {
            withTimeout(deadline) { reader.read().awaitOrThrowRejection() }
        } catch (t: TimeoutCancellationException) {
            throw t
        } catch (e: Throwable) {
            return when (classifyStreamRejection(e)) {
                is BrowserStreamRejection.PeerAborted, BrowserStreamRejection.PeerAbortedWithoutCode -> ReadResult.Reset
                BrowserStreamRejection.NotPeerAbort -> ReadResult.End
            }
        }
    if (chunk.done) return ReadResult.End
    return ReadResult.Data(chunk.value!!.uint8ArrayToReadBuffer())
}

private suspend fun writeChunk(
    writer: WritableStreamDefaultWriterJs,
    buffer: ReadBuffer,
    deadline: Duration,
): BytesWritten {
    val n = buffer.remaining()
    if (n == 0) return BytesWritten(0)
    val chunk = buffer.toJsUint8Array(n)
    try {
        withTimeout(deadline) { writer.write(chunk).awaitOrThrowRejection() }
    } catch (t: TimeoutCancellationException) {
        throw t
    } catch (e: Throwable) {
        when (val rejection = classifyStreamRejection(e)) {
            is BrowserStreamRejection.PeerAborted -> throw WebTransportStreamException(rejection.errorCode, e)
            BrowserStreamRejection.PeerAbortedWithoutCode -> throw WebTransportStreamAbortedWithoutCodeException(e)
            BrowserStreamRejection.NotPeerAbort -> throw e
        }
    }
    buffer.position(buffer.position() + n)
    return BytesWritten(n)
}

/**
 * A WHATWG abort/cancel reason carrying the WebTransport application [code] as the stream error code.
 * W3C WebTransport maps a `WebTransportError.streamErrorCode` onto RESET_STREAM / STOP_SENDING in the
 * HTTP/3 error space. Returns `null` when the runtime lacks the `WebTransportError` constructor, so the
 * caller falls back to a plain abort (RESET_STREAM with code 0). Untested in-repo (no headless WT harness);
 * the fallback guarantees no regression versus a plain abort.
 */
private fun webTransportResetReason(code: Double): JsAny? =
    js("typeof WebTransportError === 'function' ? new WebTransportError('stream reset', { streamErrorCode: code }) : null")

/** Clamp a WebTransport application error code to its 32-bit wire range, as a JS number. */
private fun Long.toStreamErrorCode(): Double = (this and 0xFFFFFFFFL).toDouble()

/** Outgoing unidirectional WebTransport stream (draft §4.1): a [ByteSink] + [Resettable]. */
internal class BrowserSendStream(
    private val writer: WritableStreamDefaultWriterJs,
) : ByteSink,
    Resettable {
    private var open = true
    override val isOpen: Boolean get() = open
    override val writePolicy: WritePolicy get() = DEFAULT_WRITE_POLICY

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = writeChunk(writer, buffer, deadline)

    /** Abort the send side (`RESET_STREAM`) carrying [errorCode] via [webTransportResetReason]. */
    override suspend fun reset(errorCode: Long) {
        open = false
        writer.abort(webTransportResetReason(errorCode.toStreamErrorCode())).await()
    }

    /** Finish the stream cleanly (FIN) — the [ByteSink.close] contract for a send-only stream. */
    override suspend fun close() {
        open = false
        writer.close().await()
    }
}

/** Incoming unidirectional WebTransport stream (draft §4.1): a [ByteSource] + [Resettable]. */
internal class BrowserReceiveStream(
    private val reader: ReadableStreamDefaultReaderJs,
) : ByteSource,
    Resettable {
    private var open = true
    override val isOpen: Boolean get() = open
    override val readPolicy: ReadPolicy get() = ReadPolicy.UntilClosed

    override suspend fun read(deadline: Duration): ReadResult {
        if (!open) return ReadResult.End
        val result = readChunk(reader, deadline)
        if (result is ReadResult.End) open = false
        return result
    }

    /** Cancel the receive side (`STOP_SENDING`) carrying [errorCode] — the [Resettable] contract for a receive-only stream. */
    override suspend fun reset(errorCode: Long) {
        open = false
        reader.cancel(webTransportResetReason(errorCode.toStreamErrorCode())).await()
    }
}

/** Bidirectional WebTransport stream (draft §4.2): a [ByteStream] that is also [HalfCloseable] + [Resettable]. */
internal class BrowserBidiStream(
    stream: WebTransportBidirectionalStreamJs,
) : ByteStream,
    HalfCloseable,
    Resettable {
    private val reader = stream.readable.getReader()
    private val writer = stream.writable.getWriter()
    private var open = true

    override val isOpen: Boolean get() = open
    override val readPolicy: ReadPolicy get() = ReadPolicy.UntilClosed
    override val writePolicy: WritePolicy get() = DEFAULT_WRITE_POLICY

    override suspend fun read(deadline: Duration): ReadResult {
        if (!open) return ReadResult.End
        return readChunk(reader, deadline)
    }

    override suspend fun write(
        buffer: ReadBuffer,
        deadline: Duration,
    ): BytesWritten = writeChunk(writer, buffer, deadline)

    /** Half-close the send side (FIN) while the read side stays open for the peer's response. */
    override suspend fun shutdownSend() {
        writer.close().await()
    }

    /** Abort both directions (`RESET_STREAM` + `STOP_SENDING`) carrying [errorCode]. */
    override suspend fun reset(errorCode: Long) {
        open = false
        val reason = webTransportResetReason(errorCode.toStreamErrorCode())
        writer.abort(reason).await()
        reader.cancel(reason).await()
    }

    /** Graceful close: FIN the send side and release the read side. */
    override suspend fun close() {
        open = false
        writer.close().await()
        reader.cancel(null).await()
    }
}
