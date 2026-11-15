package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.nativeMemoryAccess
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.socket.TransportConfig

/**
 * The platform-correct buffer factory for QUIC I/O: pooled native-memory buffers.
 *
 * Every QUIC backend hands buffer addresses straight to native code — quiche over FFM/JNI on the
 * JVM, quiche over cinterop on Linux and Apple, plus Network.framework for the Apple client's UDP
 * datagrams — so the read/write hot paths need native memory, not a managed heap buffer that would
 * force a copy at the boundary. `network()` is one process-wide, lock-free [ThreadingMode.MultiThreaded]
 * [BufferPool] seeded from [BufferFactory.Default] (from [BufferFactory.deterministic] where Default is not
 * native memory). It is the default for [withQuicConnection]/[withQuicServer] and is what
 * [QuicScope.bufferFactory] reports.
 *
 * A buffer from it is the caller's: `freeNativeMemory()`, from any thread, returns it to the pool, and one
 * never freed is reclaimed by the garbage collector like any [BufferFactory.Default] buffer. The pool rounds a
 * request up to a power-of-two size class of at least 2 KiB, so a buffer's `capacity` and its initial `limit`
 * can exceed the size asked for, and its bytes are not zeroed: wipe a secret before freeing it.
 *
 * The core accepts any [BufferFactory], so compose your own from the same parts:
 *
 * ```kotlin
 * val pool = BufferPool(ThreadingMode.MultiThreaded, maxPoolSize = 512, defaultBufferSize = 4096)
 * val factory = pool.counting() // metrics: factory.allocationCount
 * withQuicConnection(host, port, quicOptions, TransportConfig(bufferFactory = factory)) { … }
 * ```
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
fun BufferFactory.Companion.network(): BufferFactory = networkFactory

private val networkFactory: BufferFactory =
    BufferPool(
        threadingMode = ThreadingMode.MultiThreaded,
        maxPoolSize = NETWORK_POOL_BUFFERS,
        defaultBufferSize = NETWORK_POOL_MIN_BUFFER,
        factory = nativeSeed(),
    )

/** [BufferFactory.Default] where it allocates native memory, else [BufferFactory.deterministic] (Linux's Default is heap). */
private fun nativeSeed(): BufferFactory {
    val probe = BufferFactory.Default.allocate(1)
    val native = probe.nativeMemoryAccess != null
    probe.freeIfNeeded()
    return if (native) BufferFactory.Default else BufferFactory.deterministic()
}

/** Idle buffers the process-wide pool keeps, across all size classes. */
private const val NETWORK_POOL_BUFFERS = 256

/** The pool's smallest buffer, which also holds a QUIC datagram. */
private const val NETWORK_POOL_MIN_BUFFER = 2048

/**
 * Fail fast if [this] doesn't allocate native-memory buffers. Every QUIC backend hands buffer
 * *addresses* straight to native code — quiche over FFM/JNI/cinterop, and Network.framework for the
 * Apple client's UDP datagrams — so a
 * managed/heap factory ([BufferFactory.Default] / [BufferFactory.managed]) can't back QUIC I/O on
 * **any** platform, the JVM included: it would NPE deep in the binding on the first
 * `nativeMemoryAccess` dereference (cert/key load, recv buffers, sockaddrs, …). This turns that latent
 * crash into an explanatory error at connection/server setup. [network]/[deterministic] always pass.
 *
 * Cost is one throwaway 1-byte probe allocation per connection setup — negligible, and the only way
 * to ask an opaque factory whether it backs its buffers with native memory.
 */
fun BufferFactory.requireNativeMemory(): BufferFactory {
    val probe = allocate(1)
    val isNative = probe.nativeMemoryAccess != null
    probe.freeIfNeeded()
    require(isNative) {
        "QUIC requires a native-memory BufferFactory: it hands buffer addresses to native code " +
            "(quiche and the platform UDP socket). BufferFactory.Default and BufferFactory.managed() allocate " +
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
fun TransportConfig.quicBufferFactory(): BufferFactory =
    (if (bufferFactory === BufferFactory.Default) BufferFactory.network() else bufferFactory).requireNativeMemory()
