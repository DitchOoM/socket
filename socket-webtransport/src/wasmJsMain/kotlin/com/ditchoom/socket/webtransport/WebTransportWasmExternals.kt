@file:OptIn(ExperimentalWasmJsInterop::class, UnsafeWasmMemoryApi::class)

package com.ditchoom.socket.webtransport

import com.ditchoom.buffer.NativeMemoryAccess
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.allocateNative
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

// --- External declarations for the browser WebTransport + WHATWG Streams API (wasmJs) ---
//
// Mirrors the js externals (WebTransportJsExternals.kt) but in the Kotlin/Wasm interop model: every JS
// value is an opaque `JsAny` (no `dynamic`), browser objects are `external interface : JsAny`, and the
// few object literals we *construct* (the connect/close option bags) are built by `@JsFun` factories.
// `WebTransport` is reached through a `@JsFun` `new` factory rather than an `external class` so there is
// no constructor-interop ambiguity — the instance is just a `WebTransportJs` handle.

/** `new WebTransport(url, options)` — the browser global (https://www.w3.org/TR/webtransport/). */
@JsFun("(url, options) => new WebTransport(url, options)")
internal external fun createWebTransport(
    url: String,
    options: JsAny,
): WebTransportJs

/**
 * Build the `WebTransport` constructor's init bag: `{ allowPooling }`, plus `serverCertificateHashes`
 * when [serverCertificateHashes] is a non-empty array (W3C WebTransport). An empty array is omitted so
 * the browser keeps ordinary trust evaluation rather than treating it as "pin to nothing".
 */
@JsFun(
    "(allowPooling, serverCertificateHashes) => { " +
        "const o = { allowPooling }; " +
        "if (serverCertificateHashes && serverCertificateHashes.length) o.serverCertificateHashes = serverCertificateHashes; " +
        "return o; }",
)
internal external fun makeWtInit(
    allowPooling: Boolean,
    serverCertificateHashes: JsAny,
): JsAny

/** A fresh empty JS array, to accumulate `serverCertificateHashes` entries into. */
@JsFun("() => []")
internal external fun jsNewArray(): JsAny

/** Append a `{ algorithm, value }` `serverCertificateHashes` entry to [array]. */
@JsFun("(array, algorithm, value) => { array.push({ algorithm, value }); }")
internal external fun jsPushCertHash(
    array: JsAny,
    algorithm: String,
    value: JsAny,
)

/** Build the `WebTransport.close()` info bag: `{ closeCode, reason }` (draft §6). */
@JsFun("(closeCode, reason) => ({ closeCode, reason })")
internal external fun makeCloseInfo(
    closeCode: Int,
    reason: String,
): JsAny

/** A live browser `WebTransport` session object. */
internal external interface WebTransportJs : JsAny {
    /** Resolves once the session is established (the CONNECT handshake completed). */
    val ready: Promise<JsAny?>

    /** Resolves with the close info when the session ends cleanly; rejects on error. */
    val closed: Promise<WebTransportCloseInfoJs>

    val datagrams: WebTransportDatagramDuplexStream

    /** A `ReadableStream` whose chunks are peer-initiated [WebTransportBidirectionalStreamJs]s. */
    val incomingBidirectionalStreams: ReadableStreamJs

    /** A `ReadableStream` whose chunks are peer-initiated receive-only [ReadableStreamJs]s. */
    val incomingUnidirectionalStreams: ReadableStreamJs

    fun createBidirectionalStream(): Promise<WebTransportBidirectionalStreamJs>

    fun createUnidirectionalStream(): Promise<WritableStreamJs>

    fun close(info: JsAny)
}

internal external interface WebTransportCloseInfoJs : JsAny {
    val closeCode: Int
    val reason: String
}

internal external interface WebTransportBidirectionalStreamJs : JsAny {
    val readable: ReadableStreamJs
    val writable: WritableStreamJs
}

internal external interface WebTransportDatagramDuplexStream : JsAny {
    val readable: ReadableStreamJs
    val writable: WritableStreamJs
}

internal external interface ReadableStreamJs : JsAny {
    fun getReader(): ReadableStreamDefaultReaderJs
}

internal external interface ReadableStreamDefaultReaderJs : JsAny {
    /** Resolves to `{ value, done }`. For data streams `value` is a `Uint8Array`; for incoming-stream streams it's a stream object. */
    fun read(): Promise<ReadChunkJs>

    fun cancel(reason: JsAny?): Promise<JsAny?>
}

internal external interface ReadChunkJs : JsAny {
    val value: JsAny?
    val done: Boolean
}

internal external interface WritableStreamJs : JsAny {
    fun getWriter(): WritableStreamDefaultWriterJs
}

internal external interface WritableStreamDefaultWriterJs : JsAny {
    fun write(chunk: JsAny?): Promise<JsAny?>

    fun close(): Promise<JsAny?>

    fun abort(reason: JsAny?): Promise<JsAny?>
}

// --- Buffer <-> Uint8Array boundary (the sanctioned JS-edge copy, §9 / CLAUDE.md) ---
//
// Unlike js (where a buffer IS a JS Int8Array and the conversion is a view), wasm buffers live in
// linear memory — a separate ArrayBuffer from the JS heap — so each crossing is a single physical copy.
// The copy (not a view) is mandatory because every WebTransport handoff is async: a zero-copy
// `Uint8Array` view onto linear memory would be invalidated by `memory.grow()` across the `await`, and
// the Streams API may retain the chunk past the synchronous call (precedent: buffer-compression's
// JsInteropActual `toJsByteArray`/`toPlatformBuffer`).

@JsFun(
    """
(offset, length) => {
    const memory = wasmExports.memory.buffer;
    const src = new Uint8Array(memory, offset, length);
    const copy = new Uint8Array(length);
    copy.set(src);
    return copy;
}
""",
)
private external fun jsCopyFromWasmMemory(
    offset: Int,
    length: Int,
): JsAny

@JsFun(
    """
(jsArray, dstOffset) => {
    const memory = wasmExports.memory.buffer;
    const dst = new Uint8Array(memory, dstOffset, jsArray.length);
    dst.set(jsArray);
}
""",
)
private external fun jsCopyToWasmMemory(
    jsArray: JsAny,
    dstOffset: Int,
)

@JsFun("(arr) => arr.length")
private external fun jsArrayLength(arr: JsAny): Int

@JsFun("() => new Uint8Array(0)")
private external fun jsEmptyUint8Array(): JsAny

/**
 * Copy [this]'s next [length] bytes (from its current position) into a fresh JS `Uint8Array` for handing
 * to a `WritableStream`. Does **not** advance the buffer's position — the caller advances once the write
 * is accepted (mirrors the js [asUint8Array] contract).
 */
internal fun ReadBuffer.toJsUint8Array(length: Int): JsAny {
    if (length == 0) return jsEmptyUint8Array()
    val native = this as? NativeMemoryAccess
    if (native != null) {
        val offset = native.nativeAddress.toInt() + position()
        return jsCopyFromWasmMemory(offset, length)
    }
    // Non-native buffer (e.g. a managed ByteArrayBuffer): materialize at the boundary, then rewind so
    // position() is unchanged for the caller to advance after the write is accepted.
    @Suppress("NoByteArrayInProd") // WHATWG WritableStream requires a Uint8Array BufferSource at the JS edge
    val bytes = readByteArray(length)
    position(position() - length)
    return bytes.toJsUint8Array()
}

@Suppress("NoByteArrayInProd") // fallback path materializes a non-native buffer for the JS edge
private fun ByteArray.toJsUint8Array(): JsAny {
    if (isEmpty()) return jsEmptyUint8Array()
    val size = this.size
    // ByteArray lives in the Wasm-GC heap, not linear memory; stage it through a scoped linear
    // allocation, copy out to JS, and let the scope free it.
    withScopedMemoryAllocator { allocator ->
        val ptr = allocator.allocate(size)
        for (i in 0 until size) {
            (ptr + i).storeByte(this[i])
        }
        return jsCopyFromWasmMemory(ptr.address.toInt(), size)
    }
}

/** Copy a `Uint8Array` chunk from a `ReadableStream` into a fresh read-ready native [ReadBuffer]. */
internal fun JsAny.uint8ArrayToReadBuffer(): ReadBuffer {
    val length = jsArrayLength(this)
    val buf = PlatformBuffer.allocateNative(length)
    if (length > 0) {
        jsCopyToWasmMemory(this, (buf as NativeMemoryAccess).nativeAddress.toInt())
        buf.position(length)
    }
    buf.resetForRead()
    return buf
}
