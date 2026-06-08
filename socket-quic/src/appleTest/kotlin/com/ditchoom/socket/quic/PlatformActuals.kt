@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun isAppleKNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX (real network stack — always runs the harness).
// iOS/tvOS/watchOS simulators are not: by default KGP runs them via
// `simctl spawn --standalone`, which can't do Network.framework QUIC, so skip.
// The exception is the iOS simulator under the booted-mode the Gradle build
// enables (standalone=false + `QUIC_SIM_BOOTED=1`, gated on -PiosSimulatorDevice):
// there the harness runs. tvOS/watchOS never get that flag → still skip.
// See shouldSkipQuicHarnessOnSimulator's docstring (issue #81).
internal actual fun shouldSkipQuicHarnessOnSimulator(): Boolean {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return false
    val booted = getenv("QUIC_SIM_BOOTED")?.toKString() == "1"
    return !booted
}

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
