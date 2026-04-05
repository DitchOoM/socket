package com.ditchoom.socket.quic

actual fun defaultQuicServerEngine(): QuicServerEngine =
    throw UnsupportedOperationException("QUIC server is not supported in WASM environments")
