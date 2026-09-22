package com.ditchoom.socket.quic

/**
 * Apple (macOS / iOS) default QUIC engine: Cloudflare quiche (`:socket-quic-quiche`) — the one QUIC
 * engine every platform uses. The client's UDP datagrams ride an `NWConnection`; the server binds a
 * dual-stack POSIX UDP socket.
 *
 * tvOS/watchOS have no quiche target (Tier-3 build-std is unimplemented) → [UnsupportedQuicEngine]
 * in `unsupportedAppleMain`.
 */
actual val defaultQuicEngine: QuicEngine = QuicheEngine
