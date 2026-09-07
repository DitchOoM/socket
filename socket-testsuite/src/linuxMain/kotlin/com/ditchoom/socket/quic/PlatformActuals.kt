package com.ditchoom.socket.quic

actual fun isAppleKNative(): Boolean = false

actual fun isKotlinNative(): Boolean = true

actual fun quicHarnessAvailability(): QuicHarnessAvailability = QuicHarnessAvailability.Available
