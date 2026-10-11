@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.testkit.skip.SkipReason
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.time.Duration

actual fun isAppleKNative(): Boolean = true

actual fun isKotlinNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX (real network stack — always runs the harness). iOS/tvOS/watchOS
// simulators are not: KGP runs them via `simctl spawn --standalone`, outside launchd_sim's network
// services. See quicHarnessAvailability's docstring (issue #81).
//
// `QUIC_SIM_BOOTED=1` marks a booted-mode run: the root build sets it (with `standalone = false`) when a
// lane exports IOS_SIMULATOR_BOOTED_UDID, which build-apple.yaml's iOS shard does. The tvOS/watchOS
// shards still run standalone and still report the skip through `recordSkip`.
actual fun quicHarnessAvailability(): QuicHarnessAvailability {
    if (kotlin.native.Platform.osFamily == kotlin.native.OsFamily.MACOSX) return QuicHarnessAvailability.Available
    if (getenv("QUIC_SIM_BOOTED")?.toKString() == "1") return QuicHarnessAvailability.Available
    return QuicHarnessAvailability.Unavailable(
        SkipReason.SimulatorLacksNetworkServices(
            "${kotlin.native.Platform.osFamily} simulator launched by KGP via `simctl spawn --standalone`, " +
                "which runs outside launchd_sim; set QUIC_SIM_BOOTED=1 on a lane that boots a simulator " +
                "and runs with standalone=false (the root build does this when IOS_SIMULATOR_BOOTED_UDID is set)",
        ),
    )
}

actual fun armStallDump(
    after: Duration,
    label: String,
): StallDump {
    // Kotlin/Native exposes no other thread's stack: there is nothing to dump.
    return StallDump { }
}
