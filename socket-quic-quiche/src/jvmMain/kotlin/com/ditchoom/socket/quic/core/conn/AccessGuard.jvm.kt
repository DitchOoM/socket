package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.udp.attachedPrefixes

/** Local Network privacy guards macOS from version 15 on; no other desktop system withholds the local network. */
internal actual val accessGuardOfThisPlatform: AccessGuard =
    if (runsOnDarwin() && macosMajor() >= LOCAL_NETWORK_PRIVACY_FROM) {
        AccessGuard.MacosPrivacy(::attachedPrefixes)
    } else {
        AccessGuard.Unguarded
    }

// The one reading of os.name here. Once the runtime's kernel is read once in sched/SendRule.kt, this is
// `kernelOfThisRuntime == HostKernel.Darwin`.
private fun runsOnDarwin(): Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .startsWith("mac")

private fun macosMajor(): Int =
    System
        .getProperty("os.version")
        .orEmpty()
        .substringBefore('.')
        .toIntOrNull() ?: 0

private const val LOCAL_NETWORK_PRIVACY_FROM = 15
