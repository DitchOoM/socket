@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.ReadBuffer
import kotlin.native.OsFamily
import kotlin.native.Platform

/** SecTrust after the handshake where the 4.x engine uses it (the iOS family's system store); BoringSSL otherwise. */
internal actual fun platformChainTrust(options: QuicOptions): PlatformChainTrust =
    if (usesSecTrust(options)) appleSystemChainTrust else PlatformChainTrust.InHandshake

/** Apple has no native X.509 reader usable on every supported OS version; the shared DER walk reads the fields. */
internal actual fun parsePinnedLeafFieldsHere(der: ReadBuffer): X509PinFields? = parsePinnedLeafFieldsDer(der)

/**
 * Effective peer-verification decision, mirroring [applyQuicOptions]'s policy (and the Linux
 * connect path's local copy): pinned hashes verify the chain only under RequireBoth; otherwise
 * verify unless explicitly disabled, and always when CA anchors are pinned.
 */
internal fun effectiveVerifyPeer(o: QuicOptions): Boolean =
    if (o.serverCertificateHashes.isNotEmpty()) {
        o.certificateHashVerification == CertificateHashVerification.RequireBoth
    } else {
        o.verifyPeer || o.trustedCaCertificatesPem.isNotEmpty()
    }

/**
 * Whether SecTrust ([appleSystemChainTrust]) rather than BoringSSL validates the server chain: the iOS
 * family, peer verification on, no pinned anchors or leaf hashes, and [AppleTrustSource.SystemTrustStore].
 */
internal fun usesSecTrust(o: QuicOptions): Boolean =
    Platform.osFamily != OsFamily.MACOSX &&
        o.appleTrustSource == AppleTrustSource.SystemTrustStore &&
        o.trustedCaCertificatesPem.isEmpty() &&
        o.serverCertificateHashes.isEmpty() &&
        effectiveVerifyPeer(o)
