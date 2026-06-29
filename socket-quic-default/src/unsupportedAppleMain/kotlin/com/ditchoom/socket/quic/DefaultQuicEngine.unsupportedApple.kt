package com.ditchoom.socket.quic

/**
 * tvOS / watchOS default QUIC engine: unsupported. quiche has no Tier-3 Apple target (building Rust std
 * from source for tvOS/watchOS is a tracked follow-up), so — like browser JS / wasmJs — these platforms
 * throw a catchable [UnsupportedOperationException] on connect/bind instead of shipping a backend.
 */
actual val defaultQuicEngine: QuicEngine =
    UnsupportedQuicEngine(
        connectReason = "QUIC is not supported on tvOS/watchOS (no quiche target; Tier-3 build-std deferred).",
        bindReason = "QUIC server is not supported on tvOS/watchOS.",
    )
