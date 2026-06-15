package com.ditchoom.socket.quic

import com.ditchoom.socket.TransportConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Diagnostic / contract tests documenting the **known wasmJs QUIC gap** (issue #87, suite #6).
 *
 * `wasmJsTest` previously had **zero** tests — a silently-uncovered target, exactly the failure mode
 * #67/#72 fixed on Android. QUIC is unsupported on wasmJs (no raw UDP in the browser sandbox); the
 * wasmJs platform default engine is [UnsupportedQuicEngine], whose every entry throws
 * [UnsupportedOperationException].
 *
 * These **run** on wasmJs (not a silent skip) and positively assert the contract — the engine throws
 * a cleanly catchable [UnsupportedOperationException] (deliberately not `Error`/`NotImplementedError`).
 *
 * v6 Phase 2b.2 note: the public `withQuicConnection` / `withQuicServer` wrappers moved out of
 * `:socket-quic` with the engine split and return via `:socket-quic-default` (Phase 2b.4). This
 * test now exercises the unsupported engine directly until that bundle exists.
 */
class WasmJsQuicGapTests {
    private val engine =
        UnsupportedQuicEngine(
            connectReason = "QUIC is not supported in wasmJs environments (no raw UDP access)",
            bindReason = "QUIC server is not supported in WASM environments",
        )
    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    @Test
    fun quicConnect_is_an_unsupported_placeholder_on_wasmJs() =
        runQuicTest {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    engine.connect("127.0.0.1", 4433, quicOptions, TransportConfig(), 10.seconds)
                }
            assertTrue(
                error.message?.contains("not supported", ignoreCase = true) == true,
                "expected the documented wasmJs placeholder message, got: ${error.message}",
            )
        }

    @Test
    fun quicBind_is_an_unsupported_placeholder_on_wasmJs() =
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
                error.message?.contains("not supported", ignoreCase = true) == true,
                "expected the documented wasmJs placeholder message, got: ${error.message}",
            )
        }
}
