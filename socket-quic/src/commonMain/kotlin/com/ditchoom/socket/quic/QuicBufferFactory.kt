package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.ConnectionOptions

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
 * you overrode [ConnectionOptions.bufferFactory]) so they share the connection's allocation strategy:
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
 * Resolve the factory a QUIC connection should allocate from. A caller that left
 * [ConnectionOptions.bufferFactory] at its default ([BufferFactory.Default]) gets [network] — the
 * native-memory factory QUIC needs — instead of the managed default. An explicit override is honored
 * as-is (the caller opted in and owns the consequences; see [network] for why native memory matters).
 */
internal fun ConnectionOptions.quicBufferFactory(): BufferFactory =
    if (bufferFactory === BufferFactory.Default) BufferFactory.network() else bufferFactory
