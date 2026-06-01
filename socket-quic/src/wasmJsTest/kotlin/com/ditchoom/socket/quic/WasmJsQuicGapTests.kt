package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Diagnostic / contract tests documenting the **known wasmJs QUIC gap** (issue #87, suite #6).
 *
 * `wasmJsTest` previously had **zero** tests — a silently-uncovered target, exactly the failure mode
 * #67/#72 fixed on Android where a "green" suite executed zero cases. QUIC is unsupported on wasmJs for
 * the same reason as JS plus more: the browser sandbox has no raw UDP access. The `wasmJsMain` actuals
 * are documented placeholders that throw [UnsupportedOperationException].
 *
 * These mirror [com.ditchoom.socket.quic.JsQuicGapTests] (the JS Node gap tests): they **run** on wasmJs
 * (not a silent skip) and positively assert the contract — the public entry points throw a cleanly
 * catchable [UnsupportedOperationException] (deliberately not `Error`/`NotImplementedError`). If/when
 * wasmJs QUIC ever lands, these tests fail, forcing real coverage to replace this placeholder.
 */
class WasmJsQuicGapTests {
    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun withQuicConnection_is_an_unsupported_placeholder_on_wasmJs() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    withQuicConnection("127.0.0.1", 4433, quicOptions) {
                        error("unreachable: wasmJs QUIC client must not establish a connection")
                    }
                }
            assertTrue(
                error.message?.contains("not supported", ignoreCase = true) == true,
                "expected the documented wasmJs placeholder message, got: ${error.message}",
            )
        }

    @Test
    fun withQuicServer_is_an_unsupported_placeholder_on_wasmJs() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    withQuicServer(
                        port = 0,
                        tlsConfig = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused"),
                        quicOptions = quicOptions,
                    ) {
                        error("unreachable: wasmJs QUIC server must not bind")
                    }
                }
            assertTrue(
                error.message?.contains("not supported", ignoreCase = true) == true,
                "expected the documented wasmJs placeholder message, got: ${error.message}",
            )
        }
}
