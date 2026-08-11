@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.ditchoom.socket.quic

import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * Native enforcement varies by OS family because the wired X.509 parser does:
 *  - **Linux** extracts the leaf fields with BoringSSL via the `BoringSslX509` cinterop
 *    (`parsePinnedLeafFieldsLinux`), so the full constraints are
 *    [ServerCertificateConstraintSupport.Enforced].
 *  - **Every Apple target — macOS included** falls back to
 *    [ServerCertificateConstraintSupport.LeafHashOnly]. The leaf-hash pin is still enforced there; the
 *    additional W3C constraints are not.
 *
 * macOS reported `Enforced` until issue #339. That was a **misstatement, not a behaviour change**:
 * `Security.framework` does expose `SecCertificateCopyValues` on macOS, but nothing ever called it —
 * `:socket-quic-quiche`'s Apple connect path passes `parseLeafFields = null` to
 * `verifyServerCertificateHashes`, so no Apple target has ever checked validity/key-type. This type
 * documents what a platform *does*, so advertising `Enforced` there let a caller skip its own validity
 * check on the strength of a guarantee the backend was not providing. The shared
 * `QuicCertificateHashPinningTestSuite` caught it the first time it ran on Apple (issue #296): the
 * three constraint-reject cases connected successfully instead of throwing.
 *
 * Flip macOS (and, with a hand-written ASN.1-free extraction, the rest of Apple) back to `Enforced` in
 * the same change that wires a real Apple leaf-field parser — see #339.
 *
 * Resolved from [Platform.osFamily] at runtime so this single native actual covers every K/N target.
 */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() =
        when (Platform.osFamily) {
            OsFamily.LINUX -> ServerCertificateConstraintSupport.Enforced
            else -> ServerCertificateConstraintSupport.LeafHashOnly
        }
