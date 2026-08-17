@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import platform.posix.F_OK
import platform.posix.access

/**
 * Linux K/Native member of [PeerTransportParamsLayoutTestSuite] — DitchOoM/socket#388.
 *
 * Linux and Apple share one `CinteropQuicheApi` body (kept in step by
 * `CinteropQuicheApiDriftGuardTest`), so both were predicted to fail this identically before the
 * `#[repr(C)]` patch. That prediction is the reason this member exists rather than trusting the Apple
 * one: a shared body still has two separately-generated cinterop struct definitions behind it, and only
 * running both proves both.
 *
 * cinterop fixes the binding at compile time, so there is no `UnsatisfiedLinkError` skip path and
 * [wrapTestBody] stays the default pass-through — on Linux this always runs.
 */
class LinuxPeerTransportParamsLayoutTests : PeerTransportParamsLayoutTestSuite() {
    private fun certPath(name: String): String {
        val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
        return candidates.firstOrNull { access(it, F_OK) == 0 }
            ?: error("Test cert not found: $name (tried $candidates)")
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))
}
