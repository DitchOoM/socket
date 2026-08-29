@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.testkit.skip.SkipReason
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun isAppleKNative(): Boolean = true

actual fun isKotlinNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX (real network stack — always runs the harness). iOS/tvOS/watchOS
// simulators are not: KGP runs them via `simctl spawn --standalone`, outside launchd_sim's network
// services. See quicHarnessSkipReason's docstring (issue #81).
//
// `QUIC_SIM_BOOTED` is still honoured so a booted-mode lane would work the moment one exists, but
// nothing in the build or in CI sets it today — the caller reports the skip through `recordSkip`
// precisely because it fires on every simulator run, and used to do so invisibly.
actual fun quicHarnessSkipReason(): SkipReason? {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return null
    if (getenv("QUIC_SIM_BOOTED")?.toKString() == "1") return null
    return SkipReason.SimulatorLacksNetworkServices(
        "${kotlin.native.Platform.osFamily} simulator launched by KGP via `simctl spawn --standalone`, " +
            "which runs outside launchd_sim; set QUIC_SIM_BOOTED=1 on a lane that boots a simulator " +
            "and runs with standalone=false (no such lane exists yet — issue #81)",
    )
}
