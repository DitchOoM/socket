package com.ditchoom.socket.http3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure, deterministic tests for the typed [WebTransportFailure] model — no I/O, so they run on every
 * platform (incl. jsNode/wasm). They pin:
 *  1. [WebTransportFailure.describe] renders the exact wire-visible message from the typed fields (so the
 *     old string behavior stays a strict superset — nothing that read `.message` breaks);
 *  2. data-class variants are structurally comparable → deterministic equality (same input, same value);
 *  3. the variant set is handled EXHAUSTIVELY — the `else`-free `when` in [tag] is the compile-time
 *     regression guard: a new [WebTransportFailure] variant won't compile until its case is added here
 *     (and, by the same construction, at every real consumer).
 */
class WebTransportFailureTests {
    // Else-free classifier: adding a WebTransportFailure variant breaks compilation here until handled.
    private fun WebTransportFailure.tag(): String =
        when (this) {
            WebTransportFailure.NotEnabledLocally -> "not-enabled-locally"
            WebTransportFailure.PeerDoesNotSupport -> "peer-no-support"
            is WebTransportFailure.ConnectRejected -> "connect-rejected"
            WebTransportFailure.DatagramsNotEnabled -> "datagrams-not-enabled"
        }

    private val allVariants: List<WebTransportFailure> =
        listOf(
            WebTransportFailure.NotEnabledLocally,
            WebTransportFailure.PeerDoesNotSupport,
            WebTransportFailure.ConnectRejected(status = 401, authority = "example.com:443", path = "/ws"),
            WebTransportFailure.DatagramsNotEnabled,
        )

    @Test
    fun everyVariant_hasADistinctTag_coveredExhaustively() {
        val tags = allVariants.map { it.tag() }
        assertEquals(tags.size, tags.toSet().size, "each variant must map to a distinct tag")
    }

    @Test
    fun describe_rendersWireMessageFromTypedFields() {
        assertEquals(
            "WebTransport CONNECT to example.com:443/ws was rejected with status 401",
            WebTransportFailure.ConnectRejected(401, "example.com:443", "/ws").describe(),
        )
        assertTrue(WebTransportFailure.PeerDoesNotSupport.describe().contains("did not advertise WebTransport support"))
        assertTrue(WebTransportFailure.NotEnabledLocally.describe().contains("WebTransport is not enabled on this connection"))
        assertEquals(
            "QUIC datagrams are not enabled on this connection",
            WebTransportFailure.DatagramsNotEnabled.describe(),
        )
    }

    @Test
    fun connectRejected_isStructurallyDeterministic() {
        assertEquals(
            WebTransportFailure.ConnectRejected(401, "h", "/p"),
            WebTransportFailure.ConnectRejected(401, "h", "/p"),
        )
        assertNotEquals(
            WebTransportFailure.ConnectRejected(401, "h", "/p"),
            WebTransportFailure.ConnectRejected(404, "h", "/p"),
        )
        // status is first-class — read off the field, not parsed from the message.
        assertEquals(401, WebTransportFailure.ConnectRejected(401, "h", "/p").status)
    }
}
