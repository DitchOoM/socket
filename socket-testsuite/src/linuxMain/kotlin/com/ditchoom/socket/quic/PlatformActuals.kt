@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.socket.testkit.skip.SkipReason
import kotlinx.cinterop.toKString
import platform.posix.getenv

actual fun isAppleKNative(): Boolean = false

actual fun quicHarnessSkipReason(): SkipReason? = null

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
