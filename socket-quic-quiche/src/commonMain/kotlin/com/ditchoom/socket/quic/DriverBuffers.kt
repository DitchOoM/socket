package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode

/**
 * Where a driver allocates a class of buffers (datagram receives, stream reads) and frees them back to.
 *
 * A factory the caller chose is used as given, so whatever pooling it composes (the shared
 * [BufferFactory.network] pool, a counter or size limit over it, the caller's own pool) is the caller's
 * and every buffer is freed to the pool it came from. Only a factory known not to pool, [BufferFactory.Default]
 * or [BufferFactory.deterministic], gets a pool of the driver's own: stacking one on a pooled factory
 * would free one pool's buffer into another.
 */
sealed class DriverBuffers(
    factory: BufferFactory,
) : BufferFactory by factory {
    /** Drops the buffers this holds back for reuse. */
    abstract fun clear()

    /** A pool the driver built on a factory that does not pool. */
    class Owned(
        val pool: BufferPool,
    ) : DriverBuffers(pool) {
        override fun clear() = pool.clear()
    }

    /** The caller's factory, whose pooling is the caller's to clear. */
    class Callers(
        factory: BufferFactory,
    ) : DriverBuffers(factory) {
        override fun clear() = Unit
    }

    companion object {
        fun of(
            factory: BufferFactory,
            maxPoolSize: Int,
            bufferSize: Int,
        ): DriverBuffers =
            if (factory === BufferFactory.Default || factory === BufferFactory.deterministic()) {
                Owned(
                    BufferPool(
                        threadingMode = ThreadingMode.MultiThreaded,
                        maxPoolSize = maxPoolSize,
                        defaultBufferSize = bufferSize,
                        factory = factory,
                    ),
                )
            } else {
                Callers(factory)
            }
    }
}
