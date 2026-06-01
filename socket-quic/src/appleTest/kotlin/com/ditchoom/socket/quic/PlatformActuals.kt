package com.ditchoom.socket.quic

internal actual fun isAppleKNative(): Boolean = true

// macOS K/N is OsFamily.MACOSX; iOS/tvOS/watchOS simulators are not — see
// isAppleSimulator's docstring (issue #81).
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual fun isAppleSimulator(): Boolean = kotlin.native.Platform.osFamily != kotlin.native.OsFamily.MACOSX
