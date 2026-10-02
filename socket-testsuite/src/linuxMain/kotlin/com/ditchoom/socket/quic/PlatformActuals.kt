package com.ditchoom.socket.quic

import kotlin.time.Duration

actual fun isAppleKNative(): Boolean = false

actual fun isKotlinNative(): Boolean = true

actual fun quicHarnessAvailability(): QuicHarnessAvailability = QuicHarnessAvailability.Available

actual fun armStallDump(
    after: Duration,
    label: String,
): StallDump {
    // Kotlin/Native exposes no other thread's stack: there is nothing to dump.
    return StallDump { }
}
