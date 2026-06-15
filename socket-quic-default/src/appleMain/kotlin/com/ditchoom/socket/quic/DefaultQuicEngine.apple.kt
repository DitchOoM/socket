package com.ditchoom.socket.quic

/** Apple default QUIC engine: Network.framework system QUIC (`:socket-quic-nw`). */
actual val defaultQuicEngine: QuicEngine = NetworkEngine
