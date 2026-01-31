package com.ditchoom.socket

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Platform-specific socket configuration.
 *
 * These settings affect platform-level I/O behavior:
 * - On Linux: io_uring queue depth and retry behavior
 * - On other platforms: Currently no-op, but available for future use
 *
 * Configure before creating sockets:
 * ```kotlin
 * PlatformSocketConfig.configure {
 *     ioQueueDepth = 2048          // Default: 1024
 *     ioQueueRetries = 20          // Default: 10
 *     ioRetryDelay = 2.milliseconds // Default: 1ms
 * }
 * ```
 *
 * Note: Changes take effect on next io_uring initialization (after cleanup or first use).
 */
object PlatformSocketConfig {
    /**
     * Size of the I/O submission queue.
     * - Linux: io_uring SQ/CQ depth (each entry ~64 bytes)
     * - Higher values support more concurrent operations but use more memory
     * - Default: 1024 (supports ~1000 concurrent socket operations)
     */
    var ioQueueDepth: Int = 1024
        private set

    /**
     * Maximum retries when I/O queue is full.
     * - Operations retry with exponential backoff when queue is temporarily full
     * - Default: 10 retries (~10-20ms total wait with backoff)
     */
    var ioQueueRetries: Int = 10
        private set

    /**
     * Base delay between retries when I/O queue is full.
     * - Actual delay uses exponential backoff: delay * retryNumber
     * - Default: 1ms (so retries wait 1ms, 2ms, 4ms, ...)
     */
    var ioRetryDelay: Duration = 1.milliseconds
        private set

    /**
     * Configure platform socket settings.
     *
     * @param block Configuration block
     */
    fun configure(block: ConfigBuilder.() -> Unit) {
        ConfigBuilder().apply(block).apply {
            this@PlatformSocketConfig.ioQueueDepth = ioQueueDepth
            this@PlatformSocketConfig.ioQueueRetries = ioQueueRetries
            this@PlatformSocketConfig.ioRetryDelay = ioRetryDelay
        }
    }

    /**
     * Reset to default values.
     */
    fun reset() {
        ioQueueDepth = 1024
        ioQueueRetries = 10
        ioRetryDelay = 1.milliseconds
    }

    /**
     * Configure for client-side usage.
     *
     * Optimizes for lower memory usage with smaller queue depth.
     * Suitable for applications making a small number of connections.
     *
     * Settings:
     * - ioQueueDepth: 256 (supports ~250 concurrent operations)
     * - ioQueueRetries: 5
     * - ioRetryDelay: 1ms
     */
    fun configureForClient() {
        ioQueueDepth = 256
        ioQueueRetries = 5
        ioRetryDelay = 1.milliseconds
    }

    /**
     * Configure for server-side usage.
     *
     * Optimizes for high concurrency with larger queue depth.
     * Suitable for servers handling many simultaneous connections.
     *
     * Settings:
     * - ioQueueDepth: 4096 (supports ~4000 concurrent operations)
     * - ioQueueRetries: 20
     * - ioRetryDelay: 1ms
     */
    fun configureForServer() {
        ioQueueDepth = 4096
        ioQueueRetries = 20
        ioRetryDelay = 1.milliseconds
    }

    class ConfigBuilder {
        var ioQueueDepth: Int = PlatformSocketConfig.ioQueueDepth
        var ioQueueRetries: Int = PlatformSocketConfig.ioQueueRetries
        var ioRetryDelay: Duration = PlatformSocketConfig.ioRetryDelay
    }
}
