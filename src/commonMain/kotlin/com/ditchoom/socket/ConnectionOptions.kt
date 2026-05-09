package com.ditchoom.socket

import com.ditchoom.buffer.pool.BufferPool
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure configuration for socket connections.
 *
 * Buffer allocation is controlled entirely by [bufferPool]. The pool feeds both
 * stream-processor chunk staging on receive and pooled allocation on send (via
 * `BufferPool : BufferFactory`). Construct a custom pool to tune threading mode,
 * pool size, default buffer size, or seed factory.
 *
 * [defaultBufferSize] is the fallback allocation size used by the codec send path
 * when [com.ditchoom.buffer.codec.WireSize.BackPatch] is reported (variable-length
 * encodes whose size isn't known up front). Pre-allocation for
 * [com.ditchoom.buffer.codec.WireSize.Exact] uses the codec's reported size.
 */
data class ConnectionOptions(
    val socketOptions: SocketOptions = SocketOptions(),
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 15.seconds,
    val writeTimeout: Duration = 15.seconds,
    val defaultBufferSize: Int = 8192,
    val bufferPool: BufferPool = BufferPool(),
)
