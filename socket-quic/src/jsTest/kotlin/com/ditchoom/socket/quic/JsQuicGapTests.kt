package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Diagnostic / contract tests documenting the **known JS (Node) QUIC gap** (issue #74, Task 2).
 *
 * QUIC is not implemented on JS: a koffi-backed Node binding was explored and deferred on
 * zero-copy grounds. The JS platform default engine is [UnsupportedQuicEngine], whose every entry
 * throws [UnsupportedOperationException].
 *
 * Crucially these are **not silent skips** — the failure mode #67/#72 fixed on Android, where a
 * "green" suite actually executed zero QUIC cases. They run on `jsNode` in CI and positively
 * assert the contract: the engine throws [UnsupportedOperationException] (a cleanly catchable
 * signal, deliberately not [Error]/`NotImplementedError`).
 *
 * v6 Phase 2b.2 note: the public `withQuicConnection` / `withQuicServer` wrappers moved out of
 * `:socket-quic` with the engine split and return via `:socket-quic-default` (Phase 2b.4). This
 * test now exercises the unsupported engine directly until that bundle exists.
 */
class JsQuicGapTests {
    private val engine =
        UnsupportedQuicEngine(
            connectReason = "QUIC is not yet implemented on JS. Track feature/socket-quic-js-wip for progress.",
            bindReason = "QUIC server is not supported on JS.",
        )
    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun quicConnect_is_an_unsupported_placeholder_on_js() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    engine.connect("127.0.0.1", 4433, quicOptions, TransportConfig(), 10.seconds)
                }
            assertTrue(
                error.message?.contains("not yet implemented", ignoreCase = true) == true,
                "expected the documented JS placeholder message, got: ${error.message}",
            )
        }

    @Test
    fun quicBind_is_an_unsupported_placeholder_on_js() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    engine.bind(
                        port = 0,
                        host = null,
                        tlsConfig = QuicTlsConfig(certChainPath = "unused", privKeyPath = "unused"),
                        quicOptions = quicOptions,
                        timeout = 10.seconds,
                    )
                }
            assertTrue(
                error.message?.contains("not supported on JS", ignoreCase = true) == true,
                "expected the documented JS placeholder message, got: ${error.message}",
            )
        }
}
