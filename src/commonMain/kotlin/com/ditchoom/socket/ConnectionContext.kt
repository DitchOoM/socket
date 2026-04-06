package com.ditchoom.socket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling

/**
 * Owns the resources (buffer pool, pooled factory) for a single connection's lifetime.
 *
 * Created from [ConnectionOptions] at connect time, closed when the connection closes.
 * This ensures exactly one pool per connection with deterministic cleanup.
 */
class ConnectionContext(val options: ConnectionOptions) {
    val pool: BufferPool = BufferPool(
        threadingMode = options.threadingMode,
        maxPoolSize = options.maxPoolSize,
        defaultBufferSize = options.defaultBufferSize,
        factory = options.bufferFactory,
    )
    val bufferFactory: BufferFactory = options.bufferFactory.withPooling(pool)

    fun close() {
        pool.clear()
    }
}
