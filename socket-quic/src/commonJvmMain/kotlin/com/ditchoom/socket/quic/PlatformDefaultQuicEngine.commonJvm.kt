package com.ditchoom.socket.quic

/** JVM + Android default QUIC engine: quiche. Declared in `commonJvmMain` to cover both targets. */
internal actual val platformDefaultQuicEngine: QuicEngine = QuicheEngine
