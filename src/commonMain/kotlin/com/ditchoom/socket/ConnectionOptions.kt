package com.ditchoom.socket

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.pool.ThreadingMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for [SocketConnection].
 *
 * Bundles socket options, timeouts, and buffer pool sizing into a single object.
 */
data class ConnectionOptions(
    val socketOptions: SocketOptions = SocketOptions(),
    val allocationZone: AllocationZone = AllocationZone.Direct,
    val connectionTimeout: Duration = 15.seconds,
    val readTimeout: Duration = 15.seconds,
    val writeTimeout: Duration = 15.seconds,
    val defaultBufferSize: Int = 8192,
    val maxPoolSize: Int = 64,
    val threadingMode: ThreadingMode = ThreadingMode.SingleThreaded,
)
