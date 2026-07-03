package com.ditchoom.socket.transport

/**
 * The IP-level family a [Transport] rides on — the axis the staggered fallback race is drawn across
 * (RFC_TRANSPORT_FALLBACK §5). QUIC and WebTransport **share fate**: both ride UDP/HTTP-3, so on a
 * UDP-blocked network both fail the same way. Racing them against each other is pointless; the
 * meaningful race is UDP-family vs TCP-family, so [FallbackTransport]'s stagger gives one family a
 * head start and runs sequentially *within* each family.
 */
enum class TransportFamily {
    /** UDP/QUIC family: QUIC, WebTransport. */
    Udp,

    /** TCP family: TCP, WebSocket — and the conservative default for transports that don't declare. */
    Tcp,
}
