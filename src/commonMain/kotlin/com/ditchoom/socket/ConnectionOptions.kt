package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure configuration for socket connections.
 *
 * Buffer allocation is controlled by [bufferFactory]. Pass a leaf factory
 * (e.g. [BufferFactory.Default], `BufferFactory.managed()`, `BufferFactory.shared()`).
 * Socket internals build whatever pools they need from this factory; pool topology
 * is an implementation detail and never crosses the public API.
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
    val bufferFactory: BufferFactory = BufferFactory.Default,
)
