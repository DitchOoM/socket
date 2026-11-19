@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * macOS keeps BoringSSL's compiled-in default paths, which resolve the system's `/etc/ssl/cert.pem`. The iOS family
 * ships no CA file, so the bundled Mozilla roots are loaded, as the 4.x engine does.
 */
internal actual fun loadDefaultTrustAnchors(
    api: QuicheApi,
    config: QuicheConfig,
    quicOptions: QuicOptions,
    bufferFactory: BufferFactory,
) {
    if (Platform.osFamily == OsFamily.MACOSX) return
    loadPemBundle(api, config, MOZILLA_CA_ROOTS_PEM, bufferFactory)
}
