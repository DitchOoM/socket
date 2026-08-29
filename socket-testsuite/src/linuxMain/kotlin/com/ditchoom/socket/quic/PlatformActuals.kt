package com.ditchoom.socket.quic

import com.ditchoom.socket.testkit.skip.SkipReason

actual fun isAppleKNative(): Boolean = false

actual fun isKotlinNative(): Boolean = true

actual fun quicHarnessSkipReason(): SkipReason? = null
