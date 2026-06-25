@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.http3

import com.ditchoom.socket.quic.QuicTlsConfig
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv

/**
 * linuxX64 subclass of [Http3LoopbackTestSuite]. The test binary whole-archives `libquiche.a`
 * (see build.gradle.kts), so the in-process QUIC server links and runs natively. Cert/key paths are
 * probed on the filesystem relative to the test's working directory, mirroring `:socket-quic`'s
 * `LinuxQuicServerTests`. Native targets keep the default pass-through [wrapTestBody] — the cinterop
 * binding is fixed at compile time, so there's no `UnsatisfiedLinkError` to translate.
 */
class LinuxHttp3LoopbackTest : Http3LoopbackTestSuite() {
    override val timeScale: Double get() = parseTimeScale(getenv("QUIC_TEST_TIME_SCALE")?.toKString())

    private fun certPath(name: String): String {
        val candidates =
            listOf(
                "testcerts/$name",
                "socket-http3/testcerts/$name",
            )
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() =
        QuicTlsConfig(
            certChainPath = certPath("cert.crt"),
            privKeyPath = certPath("cert.key"),
        )
}
