@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv
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

internal actual fun traceRingRequested(): Boolean = getenv("QUIC_TEST_TRACE_RING")?.toKString() == "1"
