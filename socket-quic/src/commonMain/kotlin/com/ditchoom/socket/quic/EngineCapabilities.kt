package com.ditchoom.socket.quic

/**
 * Static description of what a [QuicEngine] supports, queryable before any connection exists.
 *
 * Lets common code branch on engine capability without reaching for a runtime stub — e.g. an
 * Apple consumer who has added `socket-quic-quiche` and wants migration can check
 * `engine.capabilities.supportsMigration` before passing that engine. The fine-grained per-call
 * truth (a specific connection's negotiated datagram size, a specific path's migratability) still
 * lives on the connection; this is the coarse, engine-level summary.
 */
data class EngineCapabilities(
    /** Connection migration (RFC 9000 §9) — `true` for quiche, `false` for Network.framework. */
    val supportsMigration: Boolean,
    /** Unreliable datagrams (RFC 9221) — negotiated per connection, but only when the engine can carry them at all. */
    val supportsDatagrams: Boolean,
    /** Whether the engine can [bind][QuicEngine.bind] a server, not just [connect][QuicEngine.connect]. */
    val supportsServer: Boolean,
)
