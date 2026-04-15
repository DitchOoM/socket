package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure configuration for socket connections.
 *
 * Buffer allocation is controlled entirely by [bufferFactory]. Pass a
 * [com.ditchoom.buffer.pool.BufferPool] (which implements [BufferFactory]) when you
 * want pool-recycled buffers; pass [BufferFactory.Default] or
 * [com.ditchoom.buffer.deterministic] when you don't. The socket library owns no
 * pools of its own — [bufferFactory] flows straight through to every buffer
 * allocation in the read/write path.
 *
 * [maxPoolSize], [defaultBufferSize], and [threadingMode] are advisory hints for
 * transports that build their own auxiliary pools (e.g. for stream-processor chunks)
 * and have no effect when [bufferFactory] is already a pool.
 */
data class ConnectionOptions(
    val socketOptions: SocketOptions = SocketOptions(),
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 15.seconds,
    val writeTimeout: Duration = 15.seconds,
    val defaultBufferSize: Int = 8192,
    val maxPoolSize: Int = 64,
    val threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
    val bufferFactory: BufferFactory = BufferFactory.Default,
)
