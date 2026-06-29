@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

// Apple Kotlin/Native actuals for the socket-quic unit-test harness. socket-quic's commonTest is
// engine-free (MockQuicConnection — no real Network.framework / quiche), so these run on every Apple
// target. The real-network Apple QUIC suites live in :socket-quic-nw (their harness actuals are in
// :socket-testsuite); this mirrors that logic so the simulator gate matches.

internal actual fun isAppleKNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX (real network stack — always runs). iOS/tvOS/watchOS simulators run via
// `simctl spawn --standalone` by default, which can't reach Network.framework QUIC, so skip there unless
// the Gradle build booted the simulator (QUIC_SIM_BOOTED=1). These mock-based tests don't touch the
// network, but we keep the gate identical to :socket-testsuite so behavior is uniform across modules.
internal actual fun shouldSkipQuicHarnessOnSimulator(): Boolean {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return false
    val booted = getenv("QUIC_SIM_BOOTED")?.toKString() == "1"
    return !booted
}

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
