package com.ditchoom.socket.http3

import kotlinx.coroutines.Deferred

/**
 * The request a server *promised* it would push (RFC 9114 §4.6 / §7.2.5), decoded from a
 * PUSH_PROMISE frame's field section. Mirrors the pseudo-headers the client would have sent for
 * the same resource, so an application can decide whether it wants the pushed response (and
 * [Http3ServerPush.cancel] it if not).
 */
data class Http3PromisedRequest(
    val method: String,
    val scheme: String,
    val authority: String,
    val path: String,
    val headers: List<QpackHeaderField>,
)

/**
 * A server push (RFC 9114 §4.6) surfaced to the application via [Http3Connection.pushes].
 *
 * A push has two halves that can arrive in either order on the wire: the **promise** (a
 * PUSH_PROMISE frame on a request stream, giving [promisedRequest] and the [pushId]) and the
 * **response** (a server-initiated push stream carrying the actual HEADERS+DATA). A push is
 * emitted to the application as soon as its promise is seen; [response] then suspends until the
 * push stream's response is available (returning immediately if it already arrived).
 *
 * The returned [Http3Response] is consumed exactly like a normal response (body via
 * [Http3Response.nextBodyChunk] / [Http3Response.readFullBody], then [Http3Response.close]).
 * If the push isn't wanted, call [cancel] instead of awaiting [response].
 */
class Http3ServerPush internal constructor(
    /** The push id correlating this promise with its push stream (RFC 9114 §4.6). */
    val pushId: Long,
    /** The request the server promised to fulfil with this push. */
    val promisedRequest: Http3PromisedRequest,
    private val responseDeferred: Deferred<Http3Response>,
    private val onCancel: suspend (Long) -> Unit,
) {
    /**
     * Suspends until the pushed response is available and returns it. Throws [Http3StreamException]
     * if the server withdrew the push (a CANCEL_PUSH for this id) or the connection closed before
     * the push stream delivered its response. Returns immediately if the push stream already arrived.
     */
    suspend fun response(): Http3Response = responseDeferred.await()

    /**
     * Decline this push (RFC 9114 §7.2.3): send a CANCEL_PUSH frame for [pushId] on the control
     * stream and reset the push stream if it is already open. Use this instead of [response] when
     * the application does not want the promised resource.
     */
    suspend fun cancel() = onCancel(pushId)
}
