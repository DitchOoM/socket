package com.ditchoom.socket.quic

/** JVM / Android default QUIC engine: Cloudflare quiche (`:socket-quic-quiche`). */
actual val defaultQuicEngine: QuicEngine = QuicheEngine
