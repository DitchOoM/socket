package com.ditchoom.socket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Public-API smoke tests for the wasmJs target. The shared TCP socket suites can't run here — wasmJs
 * has no raw-socket surface, so every socket operation throws — which left wasmJs with effectively zero
 * asserted coverage of its own actuals. These tests pin the wasmJs-specific public contract directly:
 * the capability surface a browser/WASM consumer sees, and the documented unsupported-operation throws.
 *
 * All entry points here are synchronous, so no coroutine test runner is needed (and unlike the
 * commonTest harness's `runTestNoTimeSkipping`, these deliberately do NOT swallow
 * `UnsupportedOperationException` — asserting it is the point).
 */
class WasmJsSocketSmokeTests {
    @Test
    fun networkCapabilities_areWebTransportAndWebSocketOnly() {
        // wasmJs exposes only the browser-provided transports — no raw TCP/QUIC.
        assertEquals(
            setOf(TransportKind.WEB_TRANSPORT, TransportKind.WEB_SOCKET),
            networkCapabilities().transports,
            "wasmJs must advertise exactly the browser-reachable transports",
        )
    }

    @Test
    fun clientSocketAllocate_throwsUnsupportedOnWasm() {
        val ex = assertFailsWith<UnsupportedOperationException> { ClientSocket.allocate() }
        assertTrue(
            ex.message?.contains("not supported in WASM") == true,
            "expected the documented WASM message, got: ${ex.message}",
        )
    }

    @Test
    fun serverSocketAllocate_throwsUnsupportedOnWasm() {
        val ex = assertFailsWith<UnsupportedOperationException> { ServerSocket.allocate() }
        assertTrue(
            ex.message?.contains("not supported in WASM") == true,
            "expected the documented WASM message, got: ${ex.message}",
        )
    }
}
