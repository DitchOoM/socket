package com.ditchoom.socket.quic

/**
 * JS default QUIC engine: none. Node has no zero-copy quiche binding yet (a koffi attempt was
 * deferred — see `feature/socket-quic-js-wip`) and browsers have no raw UDP at all, so QUIC throws.
 */
internal actual val platformDefaultQuicEngine: QuicEngine =
    UnsupportedQuicEngine(
        connectReason = "QUIC is not yet implemented on JS. Track feature/socket-quic-js-wip for progress.",
        bindReason = "QUIC server is not supported on JS.",
    )
