package com.ditchoom.socket.transport

/**
 * The transports available to assemble a default fallback chain, in the one global preference order
 * (QUIC → WebTransport → TCP → WebSocket, RFC_TRANSPORT_FALLBACK §2.2).
 *
 * Each is nullable because **client capability (Axis A) is a compile-time fact of KMP source sets**
 * (RFC §2): the platform wiring layer can only *construct* the transports its target compiles — a
 * browser has no `QuicTransport`/`TcpTransport` type to pass, so it leaves those null and supplies
 * only `webTransport` + `webSocket`. Web thus gets a two-rung chain (resolved §12 open question: the
 * browser WebTransport actual exists), native gets all four. Each instance is pre-addressed
 * (path/ALPN on the instance, RFC_UNIFIED §4).
 */
class TransportSet(
    val quic: Transport? = null,
    val webTransport: Transport? = null,
    val tcp: Transport? = null,
    val webSocket: Transport? = null,
)

/**
 * The single global ranking filtered to what this caller could build (§2.1): QUIC → WebTransport →
 * TCP → WebSocket with the absent rungs dropped. WebSocket is the universal floor and should always be
 * present — it is what makes "some servers only do WebSocket" a non-event. Wrap the result in
 * [FallbackTransport].
 */
fun defaultTransportChain(set: TransportSet): List<Transport> = listOfNotNull(set.quic, set.webTransport, set.tcp, set.webSocket)
