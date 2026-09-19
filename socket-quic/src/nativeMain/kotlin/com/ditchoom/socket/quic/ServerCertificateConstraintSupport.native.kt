@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * Native support varies by OS family because the presence of a QUIC engine does:
 *  - **Linux, macOS and iOS** all run the quiche backend, whose connect paths pass a leaf-field
 *    extractor to `verifyServerCertificateHashes` — BoringSSL via the `BoringSslX509` cinterop on
 *    Linux (`parsePinnedLeafFieldsLinux`), the shared commonMain DER walk
 *    (`parsePinnedLeafFieldsDer`) on Apple — so the full W3C constraints are
 *    [ServerCertificateConstraintSupport.Enforced].
 *  - **tvOS and watchOS** report [ServerCertificateConstraintSupport.NoQuicEngine].
 *    `:socket-quic-quiche` registers no tvOS/watchOS target and `socket-quic-default` routes those
 *    families to `UnsupportedQuicEngine`, so `connect()` throws before any certificate exists: not even
 *    the leaf hash is checked there, because no leaf is ever presented. Reporting `LeafHashOnly` would
 *    claim a pin check that nothing performs.
 *
 * This value is load-bearing: a caller may skip its own validity check on the strength of what it
 * advertises, so it must never claim a check the connect path does not run. The shared
 * `QuicCertificateHashPinningTestSuite` arms its constraint-reject cases through it.
 *
 * The `else` branch is only ever tvOS/watchOS: this module registers no other K/N families (macos*,
 * ios*, tvos*, watchos*, linuxX64, linuxArm64). Resolved from [Platform.osFamily] at runtime so this
 * single native actual covers every one of them.
 */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() =
        when (Platform.osFamily) {
            OsFamily.LINUX, OsFamily.MACOSX, OsFamily.IOS -> ServerCertificateConstraintSupport.Enforced
            else -> ServerCertificateConstraintSupport.NoQuicEngine
        }
