@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun isAppleKNative(): Boolean = false

internal actual fun shouldSkipQuicHarnessOnSimulator(): Boolean = false

internal actual fun timeScaleEnv(): String? = getenv("QUIC_TEST_TIME_SCALE")?.toKString()
