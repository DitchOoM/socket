package com.ditchoom.socket.quic

/** wasmJs default QUIC engine: none — no raw UDP access in WASM environments. */
internal actual val platformDefaultQuicEngine: QuicEngine =
    UnsupportedQuicEngine(
        connectReason = "QUIC is not supported in wasmJs environments (no raw UDP access)",
        bindReason = "QUIC server is not supported in WASM environments",
    )
