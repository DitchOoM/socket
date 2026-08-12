package com.ditchoom.socket.quic

/**
 * wasmJs has no QUIC engine at all — `defaultQuicEngine` is an `UnsupportedQuicEngine` (no raw UDP
 * access in WASM environments), so `connect()` throws before any certificate is presented and
 * [QuicOptions.serverCertificateHashes] is never consulted.
 *
 * A browser `WebTransport` session does enforce `serverCertificateHashes` and its W3C constraints
 * natively, but that is `:socket-webtransport`'s `WebTransportOptions.serverCertificateHashes` going
 * straight to the browser — a different surface from this module's QUIC engine, which is what this value
 * describes.
 */
actual val serverCertificateConstraintSupport: ServerCertificateConstraintSupport
    get() = ServerCertificateConstraintSupport.NoQuicEngine
