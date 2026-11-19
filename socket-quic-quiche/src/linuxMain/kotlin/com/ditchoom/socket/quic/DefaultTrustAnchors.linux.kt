@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import platform.posix.F_OK
import platform.posix.access

/** The distributions' system bundles, in the order the 4.x engine probes them. */
private val SYSTEM_BUNDLES =
    listOf(
        "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu/Alpine
        "/etc/pki/tls/certs/ca-bundle.crt", // RHEL/Fedora/CentOS
        "/etc/ssl/cert.pem", // Alpine/BusyBox
    )

/**
 * The first system bundle present. With none, BoringSSL's compiled-in default paths stay, which a container may lack:
 * the handshake then fails verification, typed.
 */
internal actual fun loadDefaultTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
) {
    val bundle = SYSTEM_BUNDLES.firstOrNull { access(it, F_OK) == 0 } ?: return
    loadAnchorFile(api, config, bundle, bufferFactory)
}
