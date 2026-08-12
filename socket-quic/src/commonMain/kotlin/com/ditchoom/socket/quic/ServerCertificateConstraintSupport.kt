package com.ditchoom.socket.quic

/**
 * What the current platform actually does with a leaf-hash pin supplied via
 * [QuicOptions.serverCertificateHashes] — three mutually exclusive states, so common code can reason
 * exhaustively from a single sealed surface instead of re-deriving it from the target name. Read the
 * platform value via [serverCertificateConstraintSupport].
 *
 * The distinction the three cases draw is deliberately *not* "how strict is the check" alone — it is
 * **which checks run at all**, including the case where none can, because the platform ships no QUIC
 * engine and therefore never sees a leaf. Collapsing that last case into a "hash-only" one would be the
 * same shape of misstatement issue #339 was about: a value that reads as a weaker guarantee when in fact
 * there is no connection to guarantee anything about.
 *
 * Current producers, by platform:
 *  - [Enforced] — JVM/Android (`java.security`), Linux (BoringSSL), macOS and iOS (the shared structural
 *    DER walk in `:socket-quic-quiche`'s commonMain, held to a differential test against `java.security`).
 *  - [LeafHashOnly] — **none**; see its own doc.
 *  - [NoQuicEngine] — tvOS/watchOS and JS/wasmJs, which route to `UnsupportedQuicEngine`.
 */
sealed interface ServerCertificateConstraintSupport {
    /**
     * The leaf-hash pin **and** the full W3C certificate constraints (validity ≤ 14 days, currently valid,
     * ECDSA P-256) are enforced — native accepts exactly the certificates a browser would.
     */
    data object Enforced : ServerCertificateConstraintSupport

    /**
     * There **is** a QUIC engine and it enforces the leaf-hash pin, but the W3C certificate constraints
     * are not checked on this platform: a pinned leaf that hash-matches connects even if it is expired,
     * longer-lived than 14 days, or not ECDSA P-256. Trust is still established by the pin — the platform
     * is merely more permissive than a browser about the leaf's validity period and key type.
     *
     * **This case currently has no producer.** Every platform that ships a QUIC engine supplies a
     * leaf-field parser, so all of them report [Enforced]; the platforms without an engine report
     * [NoQuicEngine], not this. It is kept because the state is genuinely reachable in the backend
     * contract — `:socket-quic-quiche`'s `verifyServerCertificateHashes` takes a nullable
     * `parseLeafFields`, so a future backend there can deliberately be hash-only, and any third-party
     * [QuicEngine] is free to check the hash and nothing else — and because a type that cannot express
     * that would force such a backend to misreport itself as one of the other two.
     */
    data object LeafHashOnly : ServerCertificateConstraintSupport

    /**
     * There is **no QUIC engine on this platform**, so no pin of any kind is enforced — not the leaf hash
     * and not the W3C constraints — because no connection can be established in the first place:
     * `connect()`/`bind()` throw [UnsupportedOperationException] from `UnsupportedQuicEngine` before any
     * certificate exists to check. [QuicOptions.serverCertificateHashes] is inert here.
     *
     * Producers: tvOS/watchOS (quiche has no Tier-3 Apple target) and JS/wasmJs (no quiche build for
     * JS/wasm; wasmJs additionally has no raw UDP, though Node's `dgram` does back `:socket-udp`). On the web
     * a browser `WebTransport` session does enforce `serverCertificateHashes` and its constraints
     * natively, but that runs through `:socket-webtransport`'s own
     * `WebTransportOptions.serverCertificateHashes`, not through this module's QUIC engine — which is why
     * this value says nothing about it.
     */
    data object NoQuicEngine : ServerCertificateConstraintSupport
}

/**
 * The [ServerCertificateConstraintSupport] level of the QUIC backend on the current platform. Lets common
 * code branch exhaustively on what `serverCertificateHashes` checking actually runs here (e.g. the shared
 * pinning test suite runs its constraint-reject cases only on an
 * [ServerCertificateConstraintSupport.Enforced] platform). It reflects the platform's capability, not any
 * particular connection's configuration.
 */
expect val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
