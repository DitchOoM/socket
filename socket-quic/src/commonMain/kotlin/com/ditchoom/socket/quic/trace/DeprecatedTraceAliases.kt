package com.ditchoom.socket.quic.trace

/*
 * Backward-compatibility shims for the trace model relocated to the transport-neutral
 * `:socket-testkit` in RFC_UNIFIED_NETWORK_TEST_HARNESS.md P0. These four types shipped in the public
 * API of socket v3.11.1 under `com.ditchoom.socket.quic.trace.*`; moving their package would break any
 * consumer that imported them. Each is re-exposed here as a deprecated `typealias` to the new
 * `com.ditchoom.socket.testkit.trace.*` type, so existing imports keep compiling (with a migration
 * warning) and the relocation stays a non-breaking, minor-version change. Remove at the next major.
 *
 * (The `internal` v1 line codec — encodeTraceLine/decodeTraceLine et al. — was never public API, so it
 * needs no shim.)
 */

@Deprecated(
    "Moved to the transport-neutral :socket-testkit. Import com.ditchoom.socket.testkit.trace.TraceSink instead.",
    ReplaceWith("TraceSink", "com.ditchoom.socket.testkit.trace.TraceSink"),
)
typealias TraceSink = com.ditchoom.socket.testkit.trace.TraceSink

@Deprecated(
    "Moved to the transport-neutral :socket-testkit. Import com.ditchoom.socket.testkit.trace.TraceEvent instead.",
    ReplaceWith("TraceEvent", "com.ditchoom.socket.testkit.trace.TraceEvent"),
)
typealias TraceEvent = com.ditchoom.socket.testkit.trace.TraceEvent

@Deprecated(
    "Moved to the transport-neutral :socket-testkit. Import com.ditchoom.socket.testkit.trace.TracePath instead.",
    ReplaceWith("TracePath", "com.ditchoom.socket.testkit.trace.TracePath"),
)
typealias TracePath = com.ditchoom.socket.testkit.trace.TracePath

@Deprecated(
    "Moved to the transport-neutral :socket-testkit. Import com.ditchoom.socket.testkit.trace.TracePathStats instead.",
    ReplaceWith("TracePathStats", "com.ditchoom.socket.testkit.trace.TracePathStats"),
)
typealias TracePathStats = com.ditchoom.socket.testkit.trace.TracePathStats
