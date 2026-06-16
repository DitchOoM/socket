package com.ditchoom.socket.webtransport

/**
 * Options for opening a client WebTransport session (the connect side).
 *
 * Deliberately **neutral**: only knobs both backings can honor belong here. Native-only transport
 * tuning (QUIC transport parameters, the engine choice, extra request headers) is reached through the
 * native [WebTransportSupport.Multiplexed] path, not bolted onto this common type — a browser build
 * could not honor it, and a field you cannot honor is exactly the impossible state v6 deletes.
 *
 * @property allowPooling whether this session's underlying HTTP/3 connection may be **shared** with
 *   other HTTP/3 sessions (draft-ietf-webtrans-http3; the browser `WebTransport` constructor's
 *   `allowPooling`). This is the *transparent* form of connection reuse — the platform decides — and is
 *   distinct from the explicit, app-controlled pooling of [WebTransportSupport.Multiplexed], where you
 *   hold the connection and open sessions on it yourself. The browser honors it directly; native
 *   treats it as "this `connect` may reuse an existing connection rather than dialing a fresh one."
 *   Default `false` (matches the browser default: a dedicated connection).
 */
data class WebTransportOptions(
    val allowPooling: Boolean = false,
)
