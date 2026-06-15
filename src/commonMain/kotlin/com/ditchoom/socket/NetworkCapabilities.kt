package com.ditchoom.socket

/**
 * The transports a given platform can actually provide. Replaces the old binary
 * `enum { FULL_SOCKET_ACCESS, WEBSOCKETS_ONLY }` with an honest set: a browser advertises
 * `{ WEB_TRANSPORT, WEB_SOCKET }`, a full-socket platform advertises all four. Common code
 * queries this and branches exhaustively rather than catching `UnsupportedOperationException`.
 */
data class NetworkCapabilities(
    val transports: Set<TransportKind>,
)

/** A transport family a platform may or may not support. */
enum class TransportKind {
    TCP,
    QUIC,
    WEB_TRANSPORT,
    WEB_SOCKET,
}

/**
 * The transports available on the current platform.
 *
 * - jvm / android / linux / apple / node → all four
 * - browser (wasmJs) → `{ WEB_TRANSPORT, WEB_SOCKET }`
 */
expect fun networkCapabilities(): NetworkCapabilities
