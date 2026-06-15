package com.ditchoom.socket.quic

/** JS default QUIC engine: unsupported (no raw UDP in the browser/Node sandbox). */
actual val defaultQuicEngine: QuicEngine =
    UnsupportedQuicEngine(
        connectReason = "QUIC is not yet implemented on JS. Track feature/socket-quic-js-wip for progress.",
        bindReason = "QUIC server is not supported on JS.",
    )
