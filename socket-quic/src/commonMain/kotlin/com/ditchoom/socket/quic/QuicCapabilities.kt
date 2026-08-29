package com.ditchoom.socket.quic

/**
 * What a live QUIC connection's **data plane** requires of the buffers a caller hands it — the
 * per-connection counterpart of [EngineCapabilities] (what an engine can do, before any connection
 * exists) and the QUIC analogue of buffer-flow's `DatagramCapabilities`, whose
 * `requiresNativeMemoryBuffers` this mirrors field-for-field.
 *
 * Read it **once**, inside the [QuicScope] block, before choosing a
 * [com.ditchoom.buffer.BufferFactory] for outbound data; the same code then allocates correctly on
 * every platform because the answer differs per backend, not per call site.
 *
 * Like `DatagramCapabilities`, the one field here inverts the usual reading of a capability: it is a
 * requirement whose **presence** constrains the caller, not a feature whose absence degrades. A
 * `false` is a real claim ("any buffer is accepted"), never an absent one.
 */
data class QuicCapabilities(
    /**
     * Writes require the buffer to be backed by native memory (a raw address), because the engine
     * reads that address directly — Cloudflare quiche over FFM / JNI / cinterop takes a pointer and a
     * length for `quiche_conn_stream_send` and `quiche_conn_dgram_send`. There is no copy at the
     * boundary; that is the library's zero-copy stance, and this flag is how the stance becomes a
     * contract the type system can state instead of a `NullPointerException` deep in the driver.
     *
     * This is a property of the **engine**, not of the platform: a heap buffer has no address on the
     * JVM either (`BufferFactory.managed()`, `BufferFactory.wrap(ByteArray)`), it only *looks*
     * platform-specific because the JVM's `BufferFactory.Default` happens to allocate direct memory
     * while Linux Kotlin/Native's allocates a managed `ByteArray`. A consumer that writes
     * `BufferFactory.Default` buffers therefore gets a working JVM app and a first-byte failure on
     * Linux — the exact bug #502 recorded — unless it honours this flag.
     *
     * When `true`, [QuicByteStream.write] and `datagramChannel().send` reject a buffer without native
     * memory with a [QuicNativeMemoryRequiredException] **before** anything is enqueued; the stream
     * and the connection are unaffected. Allocate from [QuicScope.bufferFactory] (which is
     * `BufferFactory.network()` unless `TransportConfig.bufferFactory` overrode it) or
     * `BufferFactory.deterministic()`, and free the buffer once the write returns.
     *
     * When `false`, a heap-backed buffer is accepted too — an engine that copies at its boundary, or
     * a scripted in-memory connection, says so with this value.
     */
    val requiresNativeMemoryBuffers: Boolean = false,
) {
    companion object {
        /**
         * No requirements — the honest answer for a connection whose writes copy at their boundary or
         * never reach native code at all (an in-memory double). [requiresNativeMemoryBuffers] is
         * `false` here as a real claim: such a connection accepts any buffer.
         */
        val None: QuicCapabilities = QuicCapabilities()
    }
}
