package com.ditchoom.socket.quic

import com.ditchoom.socket.ConnectionOptions
import kotlin.time.Duration

actual fun defaultQuicEngine(): QuicEngine = JsQuicEngine()

/**
 * JS QUIC engine.
 *
 * - **Browser**: QUIC is not available (no raw UDP access). Throws [UnsupportedOperationException].
 * - **Node.js**: Uses quiche via koffi FFI to load libquiche shared library.
 *   Node 23+ also has experimental `node:quic` but it's unstable.
 *   The koffi approach matches the JVM pattern — load the same libquiche.so.
 *
 * Note: JS buffers don't support [com.ditchoom.buffer.nativeMemoryAccess] (returns null).
 * Data copies between JS ArrayBuffer and native memory are unavoidable on this platform.
 */
private class JsQuicEngine : QuicEngine {
    override suspend fun <R> connect(
        hostname: String,
        port: Int,
        quicOptions: QuicOptions,
        connectionOptions: ConnectionOptions,
        timeout: Duration,
        block: suspend QuicScope.() -> R,
    ): R =
        if (isNode) {
            TODO(
                "Node.js QUIC via koffi FFI (load libquiche.so). " +
                    "JS buffers require data copies — no nativeMemoryAccess on this platform.",
            )
        } else {
            throw UnsupportedOperationException(
                "QUIC is not supported in browser environments (no raw UDP access)",
            )
        }

    override fun close() {}
}
