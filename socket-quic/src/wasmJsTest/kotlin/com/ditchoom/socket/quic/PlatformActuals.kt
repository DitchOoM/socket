package com.ditchoom.socket.quic

internal actual fun isAppleKNative(): Boolean = false

// WasmJs runs only the gap tests (no real QUIC), so time scaling is moot — no env access.
internal actual fun timeScaleEnv(): String? = null
