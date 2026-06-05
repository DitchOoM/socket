package com.ditchoom.socket.http3

import com.ditchoom.buffer.ReadBuffer

/**
 * An HTTP/3 request (RFC 9114 §4.1).
 *
 * The mandatory pseudo-header fields (`:method`, `:scheme`, `:authority`, `:path`) are modeled
 * as dedicated properties and always encoded first — pseudo-headers must precede regular
 * [headers] (RFC 9114 §4.3.1). An optional [body] is sent as a single DATA frame after the
 * HEADERS frame; pass a streaming body by writing DATA frames yourself once that API exists.
 */
data class Http3Request(
    val method: String,
    val authority: String,
    val path: String,
    val scheme: String = "https",
    val headers: List<QpackHeaderField> = emptyList(),
    val body: ReadBuffer? = null,
)
