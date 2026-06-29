package com.ditchoom.socket.webtransport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Public-API surface smoke test for the browser WebTransport provider on JS/wasmJs, compiled into both
 * jsTest and wasmJsTest from the non-gated `src/browserSmoke` dir.
 *
 * Runs on the **node** test tasks only (jsNodeTest / wasmJsNodeTest): the module's browser test tasks
 * stay Chrome-gated by design (a default CI `check` must never require Chrome — see the module build),
 * so this class is excluded from them. The real browser end-to-end coverage lives in
 * [BrowserWebTransportInteropTest] (opt-in `-PwtBrowserInterop`, real headless Chrome). Construction is
 * runtime-agnostic — the browser actual builds its provider without touching the platform `WebTransport`
 * global — so node validates the surface fully without a server or Chrome.
 *
 * It pins two consumer-visible contracts that otherwise had zero non-opt-in coverage:
 *  1. [webTransportSupport] resolves to a usable provider on JS/wasmJs (the expect/actual is wired).
 *  2. The browser provider is a plain [WebTransportSupport], NOT [WebTransportSupport.Multiplexed] — the
 *     held-connection multiplexing power is type-gated to native, by construction, not a throwing stub.
 */
class WebTransportSurfaceSmokeTest {
    @Test
    fun webTransportSupport_isAvailableOnBrowserTargets() {
        assertNotNull(webTransportSupport(), "webTransportSupport() must resolve on JS/wasmJs")
    }

    @Test
    fun browserProvider_isNotMultiplexed() {
        // The browser pools connections internally but exposes no held-connection handle, so the
        // Multiplexed power must be absent on this target (the type-gate, not a runtime throw).
        assertFalse(
            webTransportSupport() is WebTransportSupport.Multiplexed,
            "the browser WebTransport provider must not expose the native-only Multiplexed surface",
        )
    }
}
