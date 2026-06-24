@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Kotlin/Native (linuxX64 + linuxArm64) server-lifecycle test — the K/N member of the shared
 * [QuicServerLifecycleTestSuite]. Closes the gap where the suite's black-box invariants
 * (clean close within 2s, no deadlock while connections block, prompt repeated bind/close cycles,
 * streams flow completes on close) ran only on JVM + Apple, never on native Linux. Provides linux
 * cert resolution; native compiles quiche via cinterop, so there is no `UnsatisfiedLinkError` skip
 * path (the default pass-through [wrapTestBody] is correct). The JVM-only reflection assertion on
 * `serverJob` is deliberately not ported — that's an internal-state check available only on the JVM.
 */
class LinuxQuicServerLifecycleTests : QuicServerLifecycleTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
}
