package com.ditchoom.socket.quic

/**
 * QUIC path event types, mirroring quiche's `quiche_path_event_type` enum.
 *
 * The ordinal of each entry matches quiche's C enum value (RFC 9000 connection
 * migration), so `QuichePathEventType.entries[type]` decodes a raw C int directly.
 */
enum class QuichePathEventType {
    New,
    Validated,
    FailedValidation,
    Closed,
    ReusedSourceConnectionId,
    PeerMigrated,

    /** The path's validated PMTU changed. Emitted only while PMTU discovery is enabled. */
    PmtuUpdated,
}
