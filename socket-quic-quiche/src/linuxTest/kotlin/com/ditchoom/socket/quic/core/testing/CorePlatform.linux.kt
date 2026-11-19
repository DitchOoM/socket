@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic.core.testing

import com.ditchoom.socket.quic.QuicTlsConfig
import platform.posix.F_OK
import platform.posix.access

/** The committed test identity, found from the module's or the repository's directory. */
internal actual fun nativeCoreTls(): QuicTlsConfig = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

private fun certPath(name: String): String {
    val candidates = listOf("testcerts/$name", "socket-quic-quiche/testcerts/$name")
    return candidates.firstOrNull { access(it, F_OK) == 0 } ?: throw AssertionError("test cert not found: $name (tried $candidates)")
}
