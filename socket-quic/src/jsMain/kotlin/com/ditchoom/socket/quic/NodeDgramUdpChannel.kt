package com.ditchoom.socket.quic

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.unwrapFully
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Node.js [UdpChannel] backed by the `dgram` module.
 *
 * [receive] waits on an unbounded Channel that's fed by dgram's `"message"` event.
 * We queue rather than install per-call `.once` listeners so messages that arrive
 * between receive() calls are not lost. The driver only has one outstanding
 * receive() at a time, so the queue never grows unboundedly in practice.
 *
 * [send] adapts PlatformBuffer's Int8Array backing into a Node Buffer (zero-copy —
 * Node Buffer / Int8Array share ArrayBuffer storage) and suspends on dgram's
 * callback.
 *
 * Receive path is not zero-copy: dgram allocates a fresh Buffer per packet and
 * hands it to the listener. We copy those bytes into the caller's PlatformBuffer
 * via Int8Array.set — native typed-array copy, single memcpy-equivalent under
 * the hood, not per-byte.
 */
internal class NodeDgramUdpChannel(
    private val socket: dynamic,
) : UdpChannel {
    private val incoming = Channel<Int8Array>(capacity = Channel.UNLIMITED)
    private var closed = false

    init {
        val listener: (dynamic) -> Unit = { msg ->
            if (!closed) {
                // Node Buffer is a Uint8Array subclass; reinterpret the same bytes as Int8Array
                // for the Kotlin-side JsBuffer (identical storage, different view).
                val int8 = js("new Int8Array(msg.buffer, msg.byteOffset, msg.byteLength)").unsafeCast<Int8Array>()
                incoming.trySend(int8)
            }
        }
        socket.on("message", listener)

        val errorListener: (dynamic) -> Unit = { err ->
            incoming.close(RuntimeException("dgram socket error: ${err.message.unsafeCast<String>()}"))
        }
        socket.on("error", errorListener)
    }

    override suspend fun receive(buffer: PlatformBuffer): Int {
        val int8 = incoming.receive()
        val actual = buffer.unwrapFully()
        check(actual is JsBuffer) { "NodeDgramUdpChannel.receive requires a JsBuffer-backed PlatformBuffer" }
        val len = minOf(int8.length, actual.buffer.length)
        // Int8Array.set(src, offset) is a native typed-array copy — faster than a manual loop.
        actual.buffer.set(int8.subarray(0, len), 0)
        actual.position(0)
        actual.setLimit(len)
        return len
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
    ) {
        val actual = buffer.unwrapFully()
        check(actual is JsBuffer) { "NodeDgramUdpChannel.send requires a JsBuffer-backed PlatformBuffer" }
        val int8 = actual.buffer
        val offset = int8.byteOffset
        // Reinterpret the same bytes as a Node Buffer; zero-copy — dgram.send accepts any
        // Uint8Array-like and does its own memcpy into the kernel send buffer.
        val nodeBuffer = js("Buffer.from(int8.buffer, offset, len)")
        suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
            val callback: (dynamic) -> Unit = { err ->
                if (err != null) {
                    cont.resumeWithException(RuntimeException("dgram send failed: ${err.message.unsafeCast<String>()}"))
                } else {
                    cont.resume(Unit)
                }
            }
            socket.send(nodeBuffer, callback)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        incoming.close()
        try {
            socket.close()
        } catch (_: Throwable) {
            // dgram.close throws if already closed — treat as no-op.
        }
    }

    companion object {
        /**
         * Create a connected UDP/IPv4 client channel to (host, port). Mirrors the JVM
         * `DatagramChannel.open().connect(InetSocketAddress(host, port))` pattern.
         * Suspends until dgram reports the connection is ready.
         */
        suspend fun connect(
            host: String,
            port: Int,
        ): NodeDgramUdpChannel {
            val dgram = js("require('dgram')")
            val socket = dgram.createSocket("udp4")
            val ready = CompletableDeferred<Unit>()
            val onError: (dynamic) -> Unit = { err ->
                ready.completeExceptionally(
                    RuntimeException("dgram connect failed: ${err.message.unsafeCast<String>()}"),
                )
            }
            socket.once("error", onError)
            val onConnect: () -> Unit = {
                socket.off("error", onError)
                ready.complete(Unit)
            }
            socket.connect(port, host, onConnect)
            ready.await()
            return NodeDgramUdpChannel(socket)
        }
    }
}
