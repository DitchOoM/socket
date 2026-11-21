package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.udp.attachedPrefixes

/** Local Network privacy guards macOS from version 15 on; no other desktop system withholds the local network. */
internal actual val accessGuardOfThisPlatform: AccessGuard =
    if (System.getProperty("os.name").orEmpty().startsWith(MACOS) && macosMajor() >= LOCAL_NETWORK_PRIVACY_FROM) {
        AccessGuard.MacosPrivacy(::attachedPrefixes)
    } else {
        AccessGuard.Unguarded
    }

private fun macosMajor(): Int =
    System
        .getProperty("os.version")
        .orEmpty()
        .substringBefore('.')
        .toIntOrNull() ?: 0

private const val MACOS = "Mac"
private const val LOCAL_NETWORK_PRIVACY_FROM = 15
