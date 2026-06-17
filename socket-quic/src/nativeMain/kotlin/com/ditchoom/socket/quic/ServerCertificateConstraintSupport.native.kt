@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * Native enforcement varies by OS family because the X.509 parser does:
 *  - **Linux** (BoringSSL via cinterop) and **macOS** (Security.framework) expose a battle-tested parser →
 *    full constraints are [ServerCertificateConstraintSupport.Enforced].
 *  - **iOS / tvOS / watchOS** have no public cert-validity API short of hand-rolled ASN.1
 *    (`SecCertificateCopyValues` is macOS-only), so they fall back to
 *    [ServerCertificateConstraintSupport.LeafHashOnly]. The leaf-hash pin is still enforced there.
 *
 * Resolved from [Platform.osFamily] at runtime so this single native actual covers every K/N target.
 */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() =
        when (Platform.osFamily) {
            OsFamily.MACOSX, OsFamily.LINUX -> ServerCertificateConstraintSupport.Enforced
            else -> ServerCertificateConstraintSupport.LeafHashOnly
        }
