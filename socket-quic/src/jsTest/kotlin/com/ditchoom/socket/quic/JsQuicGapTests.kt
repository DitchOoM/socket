package com.ditchoom.socket.quic

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Diagnostic / contract tests documenting the **known JS (Node) QUIC gap** (issue #74, Task 2).
 *
 * QUIC is not implemented on JS: a koffi-backed Node binding was explored and deferred on
 * zero-copy grounds (see `WithQuicConnection.js.kt` + branch `feature/socket-quic-js-wip`). The
 * `jsMain` actuals are documented placeholders that throw [UnsupportedOperationException].
 *
 * Task 2's first step was meant to be diagnostic — load the Node quiche binding and handshake.
 * That step immediately surfaces the real state: there is no binding, only the documented
 * placeholders. So per the issue's Risk flag, this scopes down to *documenting the gap with a
 * test that actually runs* rather than forcing a non-existent client/server green.
 *
 * Crucially these are **not silent skips** — the failure mode #67/#72 fixed on Android, where a
 * "green" suite actually executed zero QUIC cases. They run on `jsNode` in CI and positively
 * assert the contract: the entry points throw [UnsupportedOperationException] (a cleanly catchable
 * signal, deliberately not [Error]/`NotImplementedError`, so callers using `catch (Exception)` get
 * a clean signal). If/when JS QUIC lands, these tests will fail — forcing real loopback / migration
 * coverage to replace this placeholder, exactly as intended.
 */
class JsQuicGapTests {
    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun withQuicConnection_is_an_unsupported_placeholder_on_js() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    withQuicConnection("127.0.0.1", 4433, quicOptions) {
                        error("unreachable: JS QUIC client must not establish a connection")
                    }
                }
            assertTrue(
                error.message?.contains("not yet implemented", ignoreCase = true) == true,
                "expected the documented JS placeholder message, got: ${error.message}",
            )
        }

    @Test
    fun withQuicServer_is_an_unsupported_placeholder_on_js() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    withQuicServer(
                        port = 0,
                        tlsConfig = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused"),
                        quicOptions = quicOptions,
                    ) {
                        error("unreachable: JS QUIC server must not bind")
                    }
                }
            assertTrue(
                error.message?.contains("not supported on JS", ignoreCase = true) == true,
                "expected the documented JS placeholder message, got: ${error.message}",
            )
        }
}
