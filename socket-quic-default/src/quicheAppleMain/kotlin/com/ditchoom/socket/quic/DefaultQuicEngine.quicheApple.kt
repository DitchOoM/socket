package com.ditchoom.socket.quic

/**
 * Apple (macOS / iOS) default QUIC engine: Cloudflare quiche over a POSIX UDP datapath
 * (`:socket-quic-quiche`) — the one QUIC engine every platform uses; Network.framework is TCP/TLS only.
 *
 * tvOS/watchOS have no quiche target (Tier-3 build-std is unimplemented) → [UnsupportedQuicEngine]
 * in `unsupportedAppleMain`.
 */
actual val defaultQuicEngine: QuicEngine = QuicheEngine
