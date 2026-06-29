package com.ditchoom.socket.quic

/**
 * Apple (macOS / iOS) default QUIC engine: Cloudflare quiche over a POSIX UDP datapath
 * (`:socket-quic-quiche`) — the same engine JVM/Android/Linux use. This replaced the Network.framework
 * system-QUIC backend (the deleted `:socket-quic-nw`) to delete the macos-26 libquic teardown UAF at
 * the source and unify to one QUIC engine. See the quiche-on-Apple pivot.
 *
 * tvOS/watchOS have no quiche target (Tier-3 build-std is a tracked follow-up) → [UnsupportedQuicEngine]
 * in `unsupportedAppleMain`.
 */
actual val defaultQuicEngine: QuicEngine = QuicheEngine
