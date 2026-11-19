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

/** The hashed directories, for a host that keeps no bundle file. */
private val SYSTEM_DIRECTORIES =
    listOf(
        "/etc/ssl/certs", // Debian-style
        "/etc/pki/tls/certs", // RHEL-style
        "/etc/pki/ca-trust/extracted/pem/directory-hash", // Fedora's extracted store
    )

/**
 * The system store, chosen once: a bundle file, else a hashed directory. A host with neither is refused with
 * [NoTrustAnchorsException]; the handshake never runs against an empty store.
 */
internal actual fun loadDefaultTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
) {
    when (val store = systemTrustStore({ access(it, F_OK) == 0 }, SYSTEM_BUNDLES, SYSTEM_DIRECTORIES)) {
        is SystemTrustStore.BundleFile -> loadAnchorFile(api, config, store.path, bufferFactory)
        is SystemTrustStore.HashedDirectory -> loadAnchorDirectory(api, config, store.path, bufferFactory)
        is SystemTrustStore.None -> throw NoTrustAnchorsException(store.searched)
    }
}
