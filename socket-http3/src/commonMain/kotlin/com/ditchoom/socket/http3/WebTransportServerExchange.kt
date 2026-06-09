package com.ditchoom.socket.http3

/**
 * A pending server-side WebTransport session request (an Extended CONNECT, RFC 9220), handed to the
 * `onWebTransport` handler. Inspect [authority]/[path]/[headers], then either [accept] the session
 * (returning the live [WebTransportSession]) or [reject] it with an HTTP status. A handler that
 * returns without deciding implicitly rejects with 404.
 */
class WebTransportServerExchange internal constructor(
    val authority: String,
    val path: String,
    val headers: List<QpackHeaderField>,
    private val doAccept: suspend () -> WebTransportSession,
    private val doReject: suspend (Int) -> Unit,
) {
    /** Accept the session: sends a 200 response and keeps the CONNECT stream open. Call at most once. */
    suspend fun accept(): WebTransportSession = doAccept()

    /** Reject the session with [status] (default 404), FINishing the CONNECT stream. Call at most once. */
    suspend fun reject(status: Int = 404): Unit = doReject(status)
}
