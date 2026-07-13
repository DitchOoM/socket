package com.ditchoom.socket.udp

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.unwrapFully
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/*
 * External bindings for Node's `dgram` UDP socket and the `Buffer` <-> ReadBuffer bridge (RFC Phase 4).
 *
 * `dgram` is loaded via a dynamic `require` (see createDgramSocket) rather than `@JsModule`, matching
 * the root `:socket` convention for Node-only modules: a dynamic require is never pulled into a webpack
 * browser bundle, so the JS target still compiles for the browser (where UdpSocket throws) without
 * dragging `dgram` in.
 */

/** The subset of Node's `dgram.Socket` this module drives. Objects Node hands back, never constructed. */
internal external interface DgramSocket {
    /** Bind to all interfaces on [port] (`0` = ephemeral); [callback] fires on the `listening` event. */
    fun bind(
        port: Int,
        callback: () -> Unit,
    )

    /** Bind [address]:[port] (`0` = ephemeral); [callback] fires on the `listening` event. */
    fun bind(
        port: Int,
        address: String,
        callback: () -> Unit,
    )

    /** Associate a fixed remote peer; [callback] fires on the `connect` event. */
    fun connect(
        port: Int,
        address: String,
        callback: () -> Unit,
    )

    /** Unconnected send: [msg]`[offset, offset+length)` to [address]:[port]; [callback]`(err)`. */
    fun send(
        msg: Uint8Array,
        offset: Int,
        length: Int,
        port: Int,
        address: String,
        callback: (error: Any?) -> Unit,
    )

    /** Connected send: [msg]`[offset, offset+length)` to the connected peer; [callback]`(err)`. */
    fun send(
        msg: Uint8Array,
        offset: Int,
        length: Int,
        callback: (error: Any?) -> Unit,
    )

    /** The local endpoint this socket is bound to. */
    fun address(): RInfo

    /** Set the outgoing IP TTL / hop limit (the one control-plane knob Node `dgram` exposes). */
    fun setTTL(ttl: Int)

    /** Register a single-argument event listener (`error`, `close`). */
    fun on(
        event: String,
        listener: (arg: Any?) -> Unit,
    )

    /** Register the two-argument `message` listener: `(msg: Buffer, rinfo)`. */
    fun on(
        event: String,
        listener: (
            msg: Uint8Array,
            rinfo: RInfo,
        ) -> Unit,
    )

    fun removeAllListeners()

    fun close(callback: () -> Unit)

    fun unref()
}

/** Node's address record: a **numeric** IP, its family string (`"IPv4"`/`"IPv6"`), and the port. */
internal external interface RInfo {
    val address: String
    val family: String
    val port: Int
}

/** Create a `dgram` socket of [type] (`"udp4"` / `"udp6"`) via a runtime-only require. */
internal fun createDgramSocket(type: String): DgramSocket = js("require('dgram').createSocket({ type: type })").unsafeCast<DgramSocket>()

/** Node DNS lookup (numeric result, off the event loop) via a runtime-only require. */
internal fun dnsLookup(
    host: String,
    callback: (
        error: Any?,
        address: String?,
        family: Int,
    ) -> Unit,
) {
    js("require('dns').lookup(host, callback)")
}

/**
 * Zero-copy-or-isolate view of a received Node `Buffer` as an [Int8Array]. Node delivers datagram
 * payloads sliced out of an internal shared pool (`byteOffset > 0` / `byteLength < buffer.byteLength`),
 * so a pooled view **must** be copied to isolate it from the pool before we hand ownership to the
 * caller — otherwise the next datagram could overwrite it. A dedicated buffer is viewed directly.
 * (Lifted verbatim from root `:socket`'s `NodeSocketClient.int8ArrayOf`.)
 */
internal fun nodeBufferToInt8Array(obj: Any?): Int8Array =
    js(
        """
        if (Buffer.isBuffer(obj)) {
            if (obj.byteOffset > 0 || obj.byteLength < obj.buffer.byteLength) {
                var copy = new Uint8Array(obj.byteLength);
                copy.set(new Uint8Array(obj.buffer, obj.byteOffset, obj.byteLength));
                return new Int8Array(copy.buffer, 0, obj.byteLength);
            }
            return new Int8Array(obj.buffer, obj.byteOffset, obj.byteLength)
        } else {
            var buf = Buffer.from(obj);
            return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength)
        }
        """,
    ) as Int8Array

/**
 * A [Uint8Array] over exactly the remaining bytes, for `dgram.send`. Zero-copy for the concrete
 * [JsBuffer] (a typed-array view over its backing `ArrayBuffer`); a composite/decorated [ReadBuffer]
 * falls back to a one-shot materialize (the only allocation), the same as root `:socket`'s write path.
 * Call this on a `slice()` so the caller's payload buffer is not consumed (send-does-not-consume).
 */
internal fun ReadBuffer.asUint8ArrayForSend(): Uint8Array {
    val length = remaining()
    val unwrapped = unwrapFully()
    return if (unwrapped is JsBuffer) {
        val array = unwrapped.buffer
        Uint8Array(array.buffer, array.byteOffset + unwrapped.position(), length)
    } else {
        // Composite ReadBuffer — at the dgram.send boundary a materialize is unavoidable. ByteArray IS
        // Int8Array at runtime on Kotlin/JS, so the unsafeCast is zero-copy; only readByteArray allocates.
        @Suppress("NoByteArrayInProd") // JS dgram.send boundary — no zero-copy path for composite buffers
        val bytes = readByteArray(length)
        position(position() - length) // undo the read advance (this is a throwaway slice, but stay honest)
        Uint8Array(bytes.unsafeCast<Int8Array>().buffer, 0, length)
    }
}
