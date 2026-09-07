package com.ditchoom.socket.quic

actual fun isAppleKNative(): Boolean = false

actual fun isKotlinNative(): Boolean = false

actual fun quicHarnessAvailability(): QuicHarnessAvailability = QuicHarnessAvailability.Available
