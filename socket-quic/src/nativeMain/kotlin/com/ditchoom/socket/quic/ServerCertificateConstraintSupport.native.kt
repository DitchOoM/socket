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
 *    claim a pin check that nothing performs — exactly the class of misstatement issue #339 was about.
 *
 * macOS reported `Enforced` between the constraint work landing and issue #339, while
 * `:socket-quic-quiche`'s Apple connect path still passed `parseLeafFields = null` — a **misstatement,
 * not a behaviour change**, but a load-bearing one, since a caller may skip its own validity check on
 * the strength of what this type advertises. The shared `QuicCertificateHashPinningTestSuite` caught it
 * the first time it ran on Apple (issue #296): on macOS the three constraint-reject cases connected
 * successfully instead of throwing. #339 closed the gap for real by giving every backend a parser, and
 * those three cases re-armed through this value on macOS with no test-side edit.
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
