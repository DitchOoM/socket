package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [SocketConnection].
 *
 * Bundles socket options, timeouts, buffer pool sizing, and buffer allocation strategy
 * into a single object. The [bufferFactory] controls how internal read buffers are allocated,
 * allowing callers to inject pooled, shared memory, or managed allocation strategies.
 */
data class ConnectionOptions(
    val socketOptions: SocketOptions = SocketOptions(),
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 15.seconds,
    val writeTimeout: Duration = 15.seconds,
    val defaultBufferSize: Int = 8192,
    val maxPoolSize: Int = 64,
    val threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
    val bufferFactory: BufferFactory = BufferFactory.deterministic(),
)
