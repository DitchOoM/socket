package com.ditchoom.socket.quic

/** Linux/native default QUIC engine: Cloudflare quiche (`:socket-quic-quiche`). */
actual val defaultQuicEngine: QuicEngine = QuicheEngine
