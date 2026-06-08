package com.ditchoom.socket.quic

internal actual fun isAppleKNative(): Boolean = false

internal actual fun shouldSkipQuicHarnessOnSimulator(): Boolean = false

internal actual fun timeScaleEnv(): String? = System.getenv("QUIC_TEST_TIME_SCALE")
