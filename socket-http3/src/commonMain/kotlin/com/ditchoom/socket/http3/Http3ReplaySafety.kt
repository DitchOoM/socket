package com.ditchoom.socket.http3

/**
 * Which requests an HTTP/3 server may process before the QUIC handshake is confirmed (RFC 8470 §3,
 * RFC 9114 §10.9).
 *
 * A request that arrived as 0-RTT can be a replay of a captured first flight, and the server cannot tell
 * until the handshake is confirmed. A request this answers `true` for runs the moment it arrives; any
 * other early request is held until confirmation and then processed normally, so a client is never
 * asked to retry it. A request on a confirmed connection runs at once either way.
 *
 * The default, [SafeMethods], is the safe methods of RFC 9110 §9.2.1 that can carry no side effect a
 * replay would repeat. Narrow it for a route whose GET is not safe, or widen it for a request a replay
 * cannot harm, such as one carrying an idempotency key:
 *
 * ```kotlin
 * val replaySafety = Http3ReplaySafety { request ->
 *     request.path != "/logout" &&
 *         (Http3ReplaySafety.SafeMethods.isReplaySafe(request) || request.headers.any { it.name == "idempotency-key" })
 * }
 * ```
 */
fun interface Http3ReplaySafety {
    /** Whether processing [request] twice is harmless. */
    fun isReplaySafe(request: Http3ServerRequest): Boolean

    companion object {
        /** GET, HEAD and OPTIONS. */
        val SafeMethods: Http3ReplaySafety = Http3ReplaySafety { it.method in SAFE_METHODS }

        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
