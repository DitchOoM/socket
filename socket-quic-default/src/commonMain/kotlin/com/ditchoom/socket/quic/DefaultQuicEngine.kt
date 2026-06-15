package com.ditchoom.socket.quic

/**
 * The platform's default [QuicEngine], selected at link time:
 *   - JVM / Android / Linux → `QuicheEngine` (`:socket-quic-quiche`)
 *   - Apple                 → `NetworkEngine` (`:socket-quic-nw`)
 *   - JS / wasmJs           → [UnsupportedQuicEngine] (`:socket-quic`)
 *
 * The public [withQuicConnection] / [withQuicServer] / [withQuicMux] entrypoints drive this engine.
 * Consumers that need a non-default backend on a given platform (e.g. quiche on Apple) can call the
 * engine's `connect` / `bind` directly instead of the wrappers.
 */
expect val defaultQuicEngine: QuicEngine
