package com.ditchoom.socket.quic

/**
 * Accept connections and demultiplex each one to a handler by its negotiated ALPN protocol
 * (RFC 7301) — several application protocols sharing one listener on one UDP port.
 *
 * QUIC mandates ALPN, and the TLS stack already negotiates exactly one of the protocols the
 * server offered in [QuicOptions.alpnProtocols]. So a single listener configured with, say,
 * `alpnProtocols = listOf("h3", "my-proto")` accepts both an HTTP/3 stack and a custom
 * protocol on the same port — this routes each accepted connection to the matching handler:
 *
 * ```kotlin
 * withQuicServer(port = 443, tlsConfig = tls, quicOptions = QuicOptions(listOf("h3", "my-proto"))) {
 *     connectionsByAlpn(
 *         "h3" to { serveHttp3(onRequest = { /* … */ }) },
 *         "my-proto" to { /* raw QUIC streams/datagrams */ },
 *     )
 * }
 * ```
 *
 * A client whose ALPN list does not intersect the server's never reaches a handler — the TLS
 * handshake itself fails with `no_application_protocol`, so routing only ever sees protocols
 * the listener offered. [onUnmatched] therefore fires only on a local configuration mismatch:
 * a protocol present in [QuicOptions.alpnProtocols] but missing from [routes]. That mismatch is
 * rejected up front — [QuicServer.alpnProtocols] is the offer, and every entry of it must have a
 * route, so an unrouted protocol fails here at call time instead of silently dropping connections
 * later. Servers that do not report their offer (an empty [QuicServer.alpnProtocols]) skip the
 * check, leaving [onUnmatched] as the runtime backstop. Its default closes such a connection
 * immediately (the handler-return close, `NO_ERROR`); pass your own to log or close with an
 * application error code via [QuicScope.closeWithError].
 *
 * Semantics are otherwise identical to [QuicServer.connections]: one handler invocation per
 * accepted connection, concurrent across connections, connection closed when the handler
 * returns, and the call suspends until the server is closed or the caller is cancelled.
 */
suspend fun QuicServer.connectionsByAlpn(
    vararg routes: Pair<String, suspend QuicScope.() -> Unit>,
    onUnmatched: suspend QuicScope.() -> Unit = {},
) {
    require(routes.isNotEmpty()) { "connectionsByAlpn requires at least one route" }
    val table = routes.toMap()
    require(table.size == routes.size) {
        "Duplicate ALPN route: ${routes.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys}"
    }
    // Every protocol the listener offers must be routable. The offer is what TLS selects from, so an
    // unrouted entry can only ever surface as a connection quietly falling through to onUnmatched.
    val offered = alpnProtocols
    if (offered.isNotEmpty()) {
        val unrouted = offered.filterNot { it in table }
        require(unrouted.isEmpty()) {
            "No connectionsByAlpn route for offered ALPN protocol(s) $unrouted — " +
                "the listener offers $offered but routes ${table.keys.toList()}. " +
                "Add a route, or drop the protocol from QuicOptions.alpnProtocols."
        }
    }
    connections {
        (table[negotiatedAlpn] ?: onUnmatched).invoke(this)
    }
}
