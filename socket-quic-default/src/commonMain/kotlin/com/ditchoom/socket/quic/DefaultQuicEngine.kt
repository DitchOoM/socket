package com.ditchoom.socket.quic

/**
 * The platform's default [QuicEngine], selected at link time:
 *   - JVM / Android / Linux → `QuicheEngine` (`:socket-quic-quiche`)
 *   - macOS / iOS           → `QuicheEngine` (`:socket-quic-quiche`, quiche over an NWConnection-UDP datapath)
 *   - tvOS / watchOS        → [UnsupportedQuicEngine] (no quiche target)
 *   - JS / wasmJs           → [UnsupportedQuicEngine] (`:socket-quic`)
 *
 * The public [withQuicConnection] / [withQuicServer] / [withQuicMux] entrypoints drive this engine.
 */
expect val defaultQuicEngine: QuicEngine
