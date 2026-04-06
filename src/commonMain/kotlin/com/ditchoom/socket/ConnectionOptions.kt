package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pure configuration for socket connections.
 *
 * This is a value object — it holds no resources and is safe to copy, share, or discard.
 * Resources (buffer pools, pooled factories) are created by [ConnectionContext].
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
