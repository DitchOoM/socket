package com.ditchoom.socket.webtransport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure, deterministic tests for the neutral [WebTransportFailure] model — no I/O, so they run on every
 * platform. Same guarantees as the http3-side twin: (1) [WebTransportFailure.describe] renders a stable
 * message from typed fields, (2) data-class variants are structurally comparable (deterministic
 * equality), (3) the `else`-free `when` in [tag] is the compile-time exhaustiveness guard.
 */
class WebTransportFailureTests {
    // Else-free classifier: a new WebTransportFailure variant breaks compilation here until handled.
    private fun WebTransportFailure.tag(): String =
        when (this) {
            WebTransportFailure.NotEnabledLocally -> "not-enabled-locally"
            WebTransportFailure.PeerDoesNotSupport -> "peer-no-support"
            is WebTransportFailure.ConnectRejected -> "connect-rejected"
            WebTransportFailure.DatagramsNotEnabled -> "datagrams-not-enabled"
            is WebTransportFailure.StreamAborted -> "stream-aborted"
            is WebTransportFailure.TlsHandshake -> "tls-handshake"
            is WebTransportFailure.SessionError -> "session-error"
        }

    private val allVariants: List<WebTransportFailure> =
        listOf(
            WebTransportFailure.NotEnabledLocally,
            WebTransportFailure.PeerDoesNotSupport,
            WebTransportFailure.ConnectRejected(status = 401),
            WebTransportFailure.DatagramsNotEnabled,
            WebTransportFailure.StreamAborted(errorCode = 7u),
            WebTransportFailure.TlsHandshake(badCertificate = true, detail = "untrusted"),
            WebTransportFailure.SessionError(detail = "dropped"),
        )

    @Test
    fun everyVariant_hasADistinctTag_coveredExhaustively() {
        val tags = allVariants.map { it.tag() }
        assertEquals(tags.size, tags.toSet().size, "each variant must map to a distinct tag")
    }

    @Test
    fun describe_isStableAndRenderedFromTypedFields() {
        assertEquals("WebTransport session was rejected with status 401", WebTransportFailure.ConnectRejected(401).describe())
        assertEquals("WebTransport stream aborted by peer (code 7)", WebTransportFailure.StreamAborted(7u).describe())
        assertTrue(WebTransportFailure.TlsHandshake(badCertificate = true, detail = "untrusted").describe().contains("certificate"))
        assertTrue(WebTransportFailure.TlsHandshake(badCertificate = false, detail = "alert").describe().contains("handshake"))
    }

    @Test
    fun dataVariants_areStructurallyDeterministic() {
        assertEquals(WebTransportFailure.ConnectRejected(401), WebTransportFailure.ConnectRejected(401))
        assertNotEquals(WebTransportFailure.ConnectRejected(401), WebTransportFailure.ConnectRejected(404))
        assertEquals(WebTransportFailure.StreamAborted(7u), WebTransportFailure.StreamAborted(7u))
        // typed fields are first-class
        assertEquals(7u, WebTransportFailure.StreamAborted(7u).errorCode)
        assertEquals(true, WebTransportFailure.TlsHandshake(badCertificate = true, detail = "x").badCertificate)
    }
}
