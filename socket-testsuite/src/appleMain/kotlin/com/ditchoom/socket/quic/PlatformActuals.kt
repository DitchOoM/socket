@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun isAppleKNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX (real network stack — always runs the harness).
// iOS/tvOS/watchOS simulators are not: by default KGP runs them via
// `simctl spawn --standalone`, which can't do Network.framework QUIC, so skip.
// The exception is the iOS simulator under the booted-mode the Gradle build
// enables (standalone=false + `QUIC_SIM_BOOTED=1`, gated on -PiosSimulatorDevice):
// there the harness runs. tvOS/watchOS never get that flag → still skip.
// See shouldSkipQuicHarnessOnSimulator's docstring (issue #81).
actual fun shouldSkipQuicHarnessOnSimulator(): Boolean {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return false
    val booted = getenv("QUIC_SIM_BOOTED")?.toKString() == "1"
    // Human-debug aid for the iOS-sim QUIC harness: a self-skipping suite is indistinguishable from a
    // passing one (the tests early-return → "pass"), so surface the booted state to show whether the
    // booted-mode wiring reached the test process (booted-mode=true) vs ran under KGP's default
    // --standalone (booted-mode=false → skip). The CI gate keys off the gradle-side "booted-mode wiring
    // enabled" marker instead (K/N test stdout capture is less certain); this just makes a sim run
    // self-explanatory in the log. See :socket-quic-nw build.gradle.kts (iosSimulatorDevice wiring).
    println("[QUIC-SIM-HARNESS] simulator booted-mode=$booted")
    return !booted
}

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
