@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

// Apple Kotlin/Native actuals for the socket-quic unit-test harness. socket-quic's commonTest is
// engine-free (MockQuicConnection — no real quiche), so these run on every Apple target. The
// real-network Apple QUIC suites live in :socket-quic-quiche's appleTest (their harness actuals are
// in :socket-testsuite).
//
// This file used to carry a second copy of the simulator gate "so behavior is uniform across
// modules" — an expect + five actuals with no caller anywhere, since these mock-based tests never
// touch the network. A duplicated gate that nothing calls is a drift hazard with no upside, so it
// is gone; :socket-testsuite's `quicHarnessSkipReason` is the only one.

internal actual fun isAppleKNative(): Boolean = true

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
