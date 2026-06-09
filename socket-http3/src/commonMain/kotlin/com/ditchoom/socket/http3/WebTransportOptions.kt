package com.ditchoom.socket.http3

/** The `:protocol` pseudo-header value identifying a WebTransport Extended CONNECT (RFC 9220). */
internal const val WEBTRANSPORT_PROTOCOL: String = "webtransport"

/**
 * WebTransport-over-HTTP/3 participation (RFC 9220 Extended CONNECT + RFC 9297 HTTP Datagrams +
 * draft-ietf-webtrans-http3).
 *
 * Passing a non-null instance to [withHttp3Connection] / [withHttp3Server] makes the endpoint
 * advertise the WebTransport SETTINGS on its control stream — `ENABLE_CONNECT_PROTOCOL`,
 * `H3_DATAGRAM`, `WEBTRANSPORT_MAX_SESSIONS` (and the legacy `ENABLE_WEBTRANSPORT` for interop) —
 * so the peer's [Http3Settings.webTransportSupported] resolves true.
 *
 * [maxSessions] is the number of concurrent **peer-initiated** sessions this endpoint accepts and
 * is the value put on `WEBTRANSPORT_MAX_SESSIONS`. 0 means *initiate-only*: the endpoint still
 * advertises Extended CONNECT + datagrams so it can open sessions outbound, but accepts none
 * inbound.
 *
 * WebTransport datagrams ride QUIC DATAGRAM frames (RFC 9221/9297), so the underlying
 * [com.ditchoom.socket.quic.QuicOptions] MUST enable
 * [com.ditchoom.socket.quic.DatagramOptions] (`datagrams = DatagramOptions()`) — otherwise
 * sessions still establish but datagrams are unavailable.
 */
data class WebTransportOptions(
    val maxSessions: Long = 1,
) {
    init {
        require(maxSessions >= 0) { "maxSessions must be non-negative" }
    }
}

/**
 * The SETTINGS entries an endpoint advertises to participate in WebTransport (RFC 9220 + RFC 9297 +
 * draft-ietf-webtrans-http3). Appended to the control-stream SETTINGS by both the client
 * ([Http3Connection]) and server ([Http3ServerConnection]) roles. [WebTransportOptions.maxSessions]
 * becomes `WEBTRANSPORT_MAX_SESSIONS`; the legacy `ENABLE_WEBTRANSPORT` is always 1 when enabled.
 */
internal fun webTransportSettings(options: WebTransportOptions): List<Http3Setting> =
    listOf(
        Http3Setting(Http3SettingId.ENABLE_CONNECT_PROTOCOL, 1L),
        Http3Setting(Http3SettingId.H3_DATAGRAM, 1L),
        Http3Setting(Http3SettingId.ENABLE_WEBTRANSPORT, 1L),
        Http3Setting(Http3SettingId.WEBTRANSPORT_MAX_SESSIONS, options.maxSessions),
    )
