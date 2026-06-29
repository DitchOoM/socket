package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.unwrapFully
import com.ditchoom.buffer.wrap
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

// --- External declarations for the browser WebTransport + WHATWG Streams API ---
//
// Only the members this bridge uses are declared. WebTransport is a browser global (no module).
// The streams are described structurally (external interface) — WebTransport hands them to us, we
// never construct them, so their JS runtime names don't matter.

/** The browser `WebTransport` object (https://www.w3.org/TR/webtransport/). A browser global. */
external class WebTransport(
    url: String,
    options: dynamic = definedExternally,
) {
    /** Resolves once the session is established (the CONNECT handshake completed). */
    val ready: Promise<Unit>

    /** Resolves with the close info when the session ends cleanly; rejects on error. */
    val closed: Promise<WebTransportCloseInfoJs>

    val datagrams: WebTransportDatagramDuplexStream

    /** A `ReadableStream` whose chunks are peer-initiated [WebTransportBidirectionalStreamJs]s. */
    val incomingBidirectionalStreams: ReadableStreamJs

    /** A `ReadableStream` whose chunks are peer-initiated receive-only [ReadableStreamJs]s. */
    val incomingUnidirectionalStreams: ReadableStreamJs

    fun createBidirectionalStream(): Promise<WebTransportBidirectionalStreamJs>

    fun createUnidirectionalStream(): Promise<WritableStreamJs>

    fun close(info: dynamic = definedExternally)
}

external interface WebTransportCloseInfoJs {
    val closeCode: Int
    val reason: String
}

external interface WebTransportBidirectionalStreamJs {
    val readable: ReadableStreamJs
    val writable: WritableStreamJs
}

external interface WebTransportDatagramDuplexStream {
    val readable: ReadableStreamJs
    val writable: WritableStreamJs
}

external interface ReadableStreamJs {
    fun getReader(): ReadableStreamDefaultReaderJs
}

external interface ReadableStreamDefaultReaderJs {
    /** Resolves to `{ value, done }`. For data streams `value` is a `Uint8Array`; for incoming-stream streams it's a stream object. */
    fun read(): Promise<ReadChunkJs>

    fun cancel(reason: dynamic = definedExternally): Promise<Unit>
}

external interface ReadChunkJs {
    val value: dynamic
    val done: Boolean
}

external interface WritableStreamJs {
    fun getWriter(): WritableStreamDefaultWriterJs
}

external interface WritableStreamDefaultWriterJs {
    fun write(chunk: dynamic): Promise<Unit>

    fun close(): Promise<Unit>

    fun abort(reason: dynamic = definedExternally): Promise<Unit>
}

// --- Buffer <-> Uint8Array boundary (the sanctioned JS-edge copy, §9 / CLAUDE.md) ---

/**
 * View [this]'s next [length] remaining bytes as a `Uint8Array` for handing to a `WritableStream`.
 * Zero-copy when the buffer is a [JsBuffer] (a view over its backing `Int8Array`); otherwise the one
 * sanctioned boundary copy. Does **not** advance the buffer's position — the caller does that once the
 * write is accepted.
 */
internal fun ReadBuffer.asUint8Array(length: Int): Uint8Array {
    val unwrapped = unwrapFully()
    if (unwrapped is JsBuffer) {
        val backing = unwrapped.buffer
        return Uint8Array(backing.buffer, backing.byteOffset + position(), length)
    }
    // Non-JsBuffer ReadBuffer: materialize at the boundary, then rewind so position() is unchanged.
    @Suppress("NoByteArrayInProd") // WHATWG WritableStream requires a Uint8Array BufferSource at the JS edge
    val bytes = readByteArray(length)
    position(position() - length)
    return Uint8Array(bytes.unsafeCast<Int8Array>().buffer)
}

/** Wrap a `Uint8Array` chunk from a `ReadableStream` as a read-ready [ReadBuffer] (no copy). */
internal fun Uint8Array.asReadBuffer(): ReadBuffer {
    val i8 = Int8Array(buffer, byteOffset, length)
    val buf = PlatformBuffer.wrap(i8)
    buf.position(length)
    buf.resetForRead()
    return buf
}
