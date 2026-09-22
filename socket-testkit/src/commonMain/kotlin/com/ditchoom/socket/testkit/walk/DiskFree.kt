package com.ditchoom.socket.testkit.walk

/**
 * What a walk device's disk had free when asked — the one number about a phone that cannot be read
 * from outside it, so the probe reads it and writes it down.
 */
public sealed interface DiskFree {
    /** The `diskFreeMb=` field START and HEARTBEAT lines carry. */
    public val line: String

    public data class Known(
        val bytes: Long,
    ) : DiskFree {
        override val line: String get() = "diskFreeMb=${bytes / (1024L * 1024L)}"
    }

    /** The file system would not say. */
    public data object Unknown : DiskFree {
        override val line: String get() = "diskFreeMb=unknown"
    }

    public companion object {
        /** A platform's free-space reading, where zero is how it says it could not tell. */
        public fun ofReading(bytes: Long): DiskFree = if (bytes > 0) Known(bytes) else Unknown
    }
}
