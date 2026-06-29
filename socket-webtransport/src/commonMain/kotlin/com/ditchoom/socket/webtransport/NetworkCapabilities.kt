package com.ditchoom.socket.webtransport

/** A transport that may or may not be present on a given platform. */
enum class TransportKind {
    TCP,
    QUIC,
    WEB_TRANSPORT,
    WEB_SOCKET,
}

/**
 * The coarse capability layer (MAJOR_API_REDESIGN.md §5): which transports exist at all on this
 * platform. Replaces the 5.x `enum NetworkCapabilities { FULL_SOCKET_ACCESS, WEBSOCKETS_ONLY }` with a
 * set that common code can query and exhaustively `when` over.
 *
 * browser → `{ WEB_TRANSPORT, WEB_SOCKET }` (no raw TCP/QUIC on the classpath);
 * jvm / android / native → all four.
 *
 * The *fine* layer — what advanced features a present transport supports — is the sealed-provider model
 * ([WebTransportSupport] and its [WebTransportSupport.Multiplexed]).
 */
data class NetworkCapabilities(
    val transports: Set<TransportKind>,
) {
    /** True if [kind] is available on this platform. */
    operator fun contains(kind: TransportKind): Boolean = kind in transports
}

/** This platform's coarse transport capabilities. */
expect fun networkCapabilities(): NetworkCapabilities
