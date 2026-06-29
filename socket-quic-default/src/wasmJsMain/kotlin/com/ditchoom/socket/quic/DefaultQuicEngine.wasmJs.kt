package com.ditchoom.socket.quic

/** wasmJs default QUIC engine: unsupported (no raw UDP access in WASM environments). */
actual val defaultQuicEngine: QuicEngine =
    UnsupportedQuicEngine(
        connectReason = "QUIC is not supported in wasmJs environments (no raw UDP access)",
        bindReason = "QUIC server is not supported in WASM environments",
    )
