package com.ditchoom.socket.quic

actual fun isAppleKNative(): Boolean = false

actual fun shouldSkipQuicHarnessOnSimulator(): Boolean = false

internal actual fun timeScaleEnv(): String? = System.getenv("QUIC_TEST_TIME_SCALE")
