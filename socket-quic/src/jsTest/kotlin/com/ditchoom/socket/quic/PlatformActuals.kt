package com.ditchoom.socket.quic

internal actual fun isAppleKNative(): Boolean = false

internal actual fun shouldSkipQuicHarnessOnSimulator(): Boolean = false

internal actual fun timeScaleEnv(): String? {
    // Node exposes env via process.env; guard for non-Node hosts where process is undefined.
    val raw = js("(typeof process !== 'undefined' && process.env && process.env.QUIC_TEST_TIME_SCALE) || null")
    return raw as String?
}
