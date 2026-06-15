package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.socket.TransportConfig

/**
 * The platform-correct buffer factory for QUIC I/O: native-memory buffers with explicit cleanup.
 *
 * Every QUIC backend hands buffer addresses straight to native code — quiche over FFM/JNI on the
 * JVM, quiche over cinterop on Linux, Network.framework on Apple — so the read/write hot paths need
 * native memory, not a managed heap buffer that would force a copy at the boundary. `network()` is
 * that factory (currently [BufferFactory.deterministic]); it is the default for
 * [withQuicConnection]/[withQuicServer] and is what [QuicScope.bufferFactory] reports.
 *
 * Allocate your own send buffers and datagrams from [QuicScope.bufferFactory] (which is this, unless
 * you overrode [TransportConfig.bufferFactory]) so they share the connection's allocation strategy:
 *
 * ```kotlin
 * withQuicConnection(host, port, quicOptions) {
 *     bufferFactory.allocate(11).use { out ->
 *         out.writeString("hello quic!", Charset.UTF8)
 *         out.resetForRead()
 *         stream.write(out)
 *     }
 * }
 * ```
 */
fun BufferFactory.Companion.network(): BufferFactory = BufferFactory.deterministic()

/**
 * Fail fast if [this] doesn't allocate native-memory buffers. Every QUIC backend hands buffer
 * *addresses* straight to native code — quiche over FFM/JNI/cinterop, Network.framework — so a
 * managed/heap factory ([BufferFactory.Default] / [BufferFactory.managed]) can't back QUIC I/O on
 * **any** platform, the JVM included: it would NPE deep in the binding on the first
 * `nativeMemoryAccess` dereference (cert/key load, recv buffers, sockaddrs, …). This turns that latent
 * crash into an explanatory error at connection/server setup. [network]/[deterministic] always pass.
 *
 * Cost is one throwaway 1-byte probe allocation per connection setup — negligible, and the only way
 * to ask an opaque factory whether it backs its buffers with native memory.
 */
internal fun BufferFactory.requireNativeMemory(): BufferFactory {
    val probe = allocate(1)
    val isNative = probe.nativeMemoryAccess != null
    probe.freeIfNeeded()
    require(isNative) {
        "QUIC requires a native-memory BufferFactory: it hands buffer addresses to native code " +
            "(quiche / Network.framework). BufferFactory.Default and BufferFactory.managed() allocate " +
            "on the heap and can't be used here — pass BufferFactory.network() (the default) or " +
            "BufferFactory.deterministic()."
    }
    return this
}

/**
 * Resolve the factory a QUIC connection should allocate from, and verify it is native-memory-backed.
 * A caller that left [TransportConfig.bufferFactory] at its default ([BufferFactory.Default]) gets
 * [network] — the native-memory factory QUIC needs. An explicit override is honored as-is but still
 * checked by [requireNativeMemory], so a heap factory fails with a clear message instead of a deep NPE.
 */
internal fun TransportConfig.quicBufferFactory(): BufferFactory =
    (if (bufferFactory === BufferFactory.Default) BufferFactory.network() else bufferFactory).requireNativeMemory()
