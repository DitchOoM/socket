package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Public-API smoke tests for the JS target, covering BOTH runtimes the one jsTest binary runs under:
 * Node (jsNodeTest) and the browser (jsBrowserTest). The TCP socket suites are excluded from the
 * browser task (no raw-socket surface there — see the `browser { }` filter in the root build), so the
 * browser side of the public contract — that `allocate()` throws the documented message — was only
 * worked around, never asserted. These pin it per-runtime via [isNodeJs].
 *
 * Synchronous entry points, so plain `@Test` (no coroutine runner).
 */
class JsSocketSmokeTests {
    @Test
    fun networkCapabilities_includeTcpQuicAndWebTransport() {
        // The JS capability set is static across Node/browser; assert the advertised surface.
        val transports = networkCapabilities().transports
        assertTrue(TransportKind.TCP in transports, "JS must advertise TCP")
        assertTrue(TransportKind.QUIC in transports, "JS must advertise QUIC")
        assertTrue(TransportKind.WEB_TRANSPORT in transports, "JS must advertise WebTransport")
        assertTrue(TransportKind.WEB_SOCKET in transports, "JS must advertise WebSocket")
    }

    @Test
    fun clientSocketAllocate_constructsOnNode_throwsInBrowser() {
        if (isNodeJs) {
            // Node has net.Socket — allocate() must hand back a real client socket.
            assertNotNull(ClientSocket.allocate(), "Node must construct a NodeClientSocket")
        } else {
            val ex = assertFailsWith<UnsupportedOperationException> { ClientSocket.allocate() }
            assertTrue(
                ex.message?.contains("not supported in the browser") == true,
                "expected the documented browser message, got: ${ex.message}",
            )
        }
    }

    @Test
    fun serverSocketAllocate_constructsOnNode_throwsInBrowser() {
        if (isNodeJs) {
            assertNotNull(ServerSocket.allocate(), "Node must construct a NodeServerSocket")
        } else {
            val ex = assertFailsWith<UnsupportedOperationException> { ServerSocket.allocate() }
            assertTrue(
                ex.message?.contains("not supported in the browser") == true,
                "expected the documented browser message, got: ${ex.message}",
            )
        }
    }
}
