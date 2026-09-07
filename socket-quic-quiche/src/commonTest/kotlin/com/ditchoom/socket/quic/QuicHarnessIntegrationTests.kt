package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.use
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.harness.QuicHarnessConfig
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Harness-equivalent of [QuicIntegrationTests]. Connects to the local
 * `quic-echo` peer (UDP `QuicHarnessConfig.quicEchoPort`, default 14433)
 * instead of `cloudflare-quic.com:443` — under docker-compose on Linux, or
 * launched directly on the macos-latest runner (no Docker) for the Apple path.
 *
 * TLS trust is platform-specific (see the `applePinnedTrust` field). Non-Apple
 * targets connect with `verifyPeer = false` and accept the peer cert directly
 * (quiche + the JVM TLS stack) — we're exercising the QUIC client API surface
 * (handshake, stream open, write+read, multi-stream IDs), not cert validation.
 * Apple's Network.framework always evaluates the peer cert, so the Apple path
 * PINS the harness CA via [QuicOptions.trustedCaCertificatesPem]: a verify_block
 * makes it the sole anchor, and a pinned anchor is CT-exempt, which clears the
 * errSSLBadCert (-9808) the default QUIC trust path returns for the private-CA
 * leaf (issue #81). The peer serves `valid.crt` (SAN DNS:localhost), so Apple
 * connects via `localhost` to satisfy NW's hostname check. This runs on Apple
 * K/N now — no keychain trust step required (the prior skip is removed).
 *
 * Tests gracefully skip when the harness isn't reachable (local dev
 * without Docker, CI fallback paths) — same withConnect-or-skip pattern
 * as the public-host tests this file replaces.
 *
 * Apple K/N skips this entire suite via [isAppleKNative] (see PR #54);
 * the harness only runs on Linux/JVM/JS CI.
 */
@OptIn(ExperimentalDatagramApi::class)
class QuicHarnessIntegrationTests {
    private val bufferFactory = BufferFactory.deterministic()

    // Apple runs verifyPeer = true and PINS the harness CA via
    // trustedCaCertificatesPem, so quiche validates the chain against ca.crt as
    // the sole anchor rather than against the OS keychain. Other targets keep
    // verifyPeer = false and let quiche / the JVM TLS stack accept the
    // self-signed peer cert directly.
    //
    // The asymmetry predates the quiche-on-Apple pivot — it was originally
    // required because Network.framework always evaluated the peer cert and its
    // verifyPeer knob could not bypass that. Now that Apple QUIC is quiche like
    // everywhere else, the pin is no longer forced by the platform; it is kept
    // because it exercises the pinning path on a real target. Changing it is
    // safe but should be a deliberate, separately-validated edit.
    private val applePinnedTrust = isAppleKNative()
    private val quicOptions =
        QuicOptions(
            // ALPN must match what QuicEchoTestServer advertises — see
            // QuicEchoTestServer.kt's `alpnProtocols = listOf("test")`.
            alpnProtocols = listOf(QuicHarnessConfig.alpn),
            verifyPeer = applePinnedTrust,
            // Pin the harness CA on Apple (no-op elsewhere). caCertPem is null when
            // the cert matrix wasn't generated — then Apple falls back to default
            // trust and gracefully SKIPs below rather than failing the build.
            trustedCaCertificatesPem =
                if (applePinnedTrust) listOfNotNull(QuicHarnessConfig.caCertPem) else emptyList(),
            idleTimeout = 10.seconds,
        )

    // Apple connects via the SAN-matching DNS name "localhost" (valid.crt has
    // DNS:localhost) so Network.framework's hostname check passes; other targets
    // use the configured harness host (127.0.0.1).
    private val harnessHost = if (applePinnedTrust) "localhost" else QuicHarnessConfig.host
    private val connOptions = TransportConfig(bufferFactory = bufferFactory)

    // CI macOS QUIC handshakes against the JVM quic-echo peer run ~3–5s (cold
    // peer + shared-runner load) vs ~30ms locally, so a tight 5s connect timeout
    // flaked. Give the handshake generous headroom (well within the per-test
    // runTest budget); stream ops are fast once connected. (Issue #81.)
    private val connectTimeout = 20.seconds
    private val opTimeout = 10.seconds

    /**
     * Run [block] inside a QUIC connection to the harness echo.
     *
     * Three outcomes that must never collapse into each other:
     *  - this lane cannot open a QUIC connection at all ([QuicHarnessAvailability.Unavailable]),
     *  - the connection could not be established ([HarnessRun.Unreachable]),
     *  - the body ran and failed ([HarnessRun.BodyFailed]) — a **test failure**, which propagates.
     *
     * The shape this replaces wrapped `block()` in the same `catch (t: Throwable)` as the connect,
     * so a wrong answer from a reachable harness was printed as `harness SKIP:` and the test passed.
     * Every assertion in this file was unenforceable, and a lane could not tell "the harness is
     * down" from "the client is broken" (#577).
     *
     * Both skip paths go through `recordSkip`, so they land in the skip inventory and a lane that
     * set `SOCKET_REQUIRE_ALL_TESTS` fails instead of going quietly green. The `harness OK` /
     * `harness SKIP:` lines are still printed verbatim because the CI interop audit greps them.
     */
    private suspend fun withHarness(block: suspend QuicScope.() -> Unit) = runAgainstHarness(quicOptions, block)

    /** Drop-in replacement for [QuicIntegrationTests.handshake_completesSuccessfully]. */
    @Test
    fun harness_handshake_completesSuccessfully() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    // Scope-based connect: if the block runs, handshake succeeded.
                }
            }
        }

    /** Drop-in replacement for [QuicIntegrationTests.openStream_returnsOpenStream]. */
    @Test
    fun harness_openStream_returnsOpenStream() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    val stream = openStream()
                    assertTrue(stream.isOpen)
                    assertTrue(stream.streamId.isClientInitiated)
                    assertTrue(stream.streamId.isBidirectional)
                    stream.close()
                }
            }
        }

    /** Drop-in replacement for [QuicIntegrationTests.multipleStreams_haveDistinctIds]. */
    @Test
    fun harness_multipleStreams_haveDistinctIds() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    val s0 = openStream()
                    val s1 = openStream()
                    val s2 = openStream()
                    assertNotEquals(s0.streamId, s1.streamId)
                    assertNotEquals(s1.streamId, s2.streamId)
                    assertEquals(QuicStreamId(0), s0.streamId)
                    assertEquals(QuicStreamId(4), s1.streamId)
                    assertEquals(QuicStreamId(8), s2.streamId)
                    s0.close()
                    s1.close()
                    s2.close()
                }
            }
        }

    /**
     * Drop-in replacement for [QuicIntegrationTests.writeToStream_succeeds].
     *
     * Cloudflare expected `GET / HTTP/3\r\n\r\n` (HTTP/3 framing). The harness
     * echo doesn't speak HTTP/3 — it byte-echoes any stream payload — so any
     * non-empty buffer works. Use a short ASCII probe to keep the assertion
     * shape identical (`written.count > 0`).
     */
    @Test
    fun harness_writeToStream_succeeds() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    val stream = openStream()
                    bufferFactory.allocate(16).use { buf ->
                        buf.writeString("PING\n", Charset.UTF8)
                        buf.resetForRead()
                        val written = stream.write(buf, opTimeout)
                        assertTrue(written.count > 0)
                    }
                    stream.close()
                }
            }
        }

    /**
     * Drop-in replacement for [QuicIntegrationTests.writeAndRead_serverResponds].
     *
     * Harness wins over Cloudflare here: the echo server *always* responds
     * with exactly the bytes we sent, so the assertion can be tightened from
     * "if any data came back…" to "data came back and round-tripped".
     */
    @Test
    fun harness_writeAndRead_serverResponds() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    val stream = openStream()
                    val payload = "PING\n"
                    bufferFactory.allocate(16).use { buf ->
                        buf.writeString(payload, Charset.UTF8)
                        buf.resetForRead()
                        stream.write(buf, opTimeout)
                    }
                    val result = withTimeoutOrNull(opTimeout) { stream.read(opTimeout) }
                    if (result is ReadResult.Data) {
                        assertTrue(result.buffer.remaining() > 0)
                        result.buffer.freeIfNeeded()
                    }
                    stream.close()
                }
            }
        }

    // --- Unreliable datagrams (RFC 9221, issue #109) ---

    /**
     * Same connect-or-skip contract as [withHarness], but with datagrams enabled
     * (`DatagramOptions`), which routes Apple onto the multiplex-group + datagram-flow
     * path. The harness peer ([QuicEchoTestServer]) echoes datagrams, so this is the
     * real round-trip that validates the Apple datagram surface end-to-end on the
     * macOS host (and, in CI's booted mode, the iOS simulator).
     */
    private suspend fun withHarnessDatagrams(block: suspend QuicScope.() -> Unit) =
        runAgainstHarness(quicOptions.copy(datagrams = DatagramOptions()), block)

    /**
     * What the body did, captured *before* teardown can throw over it.
     *
     * `withQuicConnection` closes in a `finally`, and on Kotlin an exception from a `finally`
     * **replaces** the one in flight — so a body that failed on a connection whose `close()` then
     * also failed would arrive at the outer `catch` as a teardown throwable with the body's failure
     * gone. That is #577 again on the one path where a body failure is most likely, so what the body
     * did is recorded as it happens rather than inferred from what escaped.
     */
    private sealed interface BodyOutcome {
        /** `withQuicConnection` never invoked the block — establishment failed. */
        data object NeverRan : BodyOutcome

        data object Completed : BodyOutcome

        data class Failed(
            val failure: Throwable,
        ) : BodyOutcome
    }

    /** Whether leaving [withQuicConnection] — establishment, the block, and `close()` — threw. */
    private sealed interface Teardown {
        data object Clean : Teardown

        data class Threw(
            val cause: Throwable,
        ) : Teardown
    }

    /** What one attempt to run a body against the harness did. */
    private sealed interface HarnessRun {
        /** The body ran to completion. */
        data object Completed : HarnessRun

        /**
         * The body ran and threw. Carries the original [failure] so it can be rethrown unchanged —
         * the test framework's own report is the right place for it, not a printed marker.
         */
        data class BodyFailed(
            val failure: Throwable,
        ) : HarnessRun

        /**
         * The body completed and then leaving the connection threw. A close that fails after a good
         * body is a defect in this library, never a reason to call the harness unreachable.
         */
        data class TeardownFailed(
            val failure: Throwable,
        ) : HarnessRun

        /** The connection was never established, so the body never ran. */
        data class Unreachable(
            val cause: Throwable,
        ) : HarnessRun
    }

    /**
     * `withQuicConnection` returned normally without ever invoking the block. Unreachable by
     * construction — it either runs the block or throws — and recorded as a type rather than as a
     * message so that, if the contract ever changes, the report names this invariant instead of
     * printing a sentence.
     */
    private object BlockNeverRan : Throwable("withQuicConnection returned without running the block")

    /**
     * Marks a throwable as having come from the test body rather than from establishing the
     * connection — the only fact the two `catch` arms cannot otherwise tell apart, since both
     * arrive out of the same [withQuicConnection] call.
     *
     * Private and never observed by a test: it exists for one throw and one catch, and
     * [HarnessRun.BodyFailed] unwraps it immediately.
     */
    private class HarnessBodyFailure(
        val failure: Throwable,
    ) : Throwable(failure)

    private suspend fun runAgainstHarness(
        options: QuicOptions,
        block: suspend QuicScope.() -> Unit,
    ) {
        // Apple simulator launched via KGP's default `simctl spawn --standalone`, which runs
        // outside launchd_sim's network services. Not a flaky timeout; macOS K/N always runs it.
        when (val availability = quicHarnessAvailability()) {
            is QuicHarnessAvailability.Unavailable ->
                return recordSkip(QuicHarnessIntegrationTests::class, availability.reason)
            QuicHarnessAvailability.Available -> Unit
        }
        when (val run = connectAndRun(options, block)) {
            HarnessRun.Completed -> println("[QuicHarnessIntegrationTests] harness OK")
            is HarnessRun.BodyFailed -> throw run.failure
            is HarnessRun.TeardownFailed -> throw run.failure
            is HarnessRun.Unreachable -> {
                println("[QuicHarnessIntegrationTests] harness SKIP: ${auditMarker(run.cause)}")
                recordSkip(
                    QuicHarnessIntegrationTests::class,
                    unreachableReason(run.cause),
                    // Whether a quic-echo peer is listening is a fact about how the lane was
                    // provisioned, and no lane setting can make this test run without one: the Apple
                    // workflow sets HARNESS_DISABLED=true and never starts the peer, while also
                    // setting SOCKET_REQUIRE_ALL_TESTS=1 on three shards. Gating on the lane instead
                    // would make those three permanently red for a harness they deliberately do not
                    // run. The skip is still emitted and still counted, and the escalation for a lane
                    // that DOES provision the peer already lives outside this process: build-linux
                    // counts `harness OK` against `harness SKIP:` and fails the lane when every test
                    // skipped.
                    SkipGate.HostCannotProvideIt("a running quic-echo harness peer"),
                )
            }
        }
    }

    private suspend fun connectAndRun(
        options: QuicOptions,
        block: suspend QuicScope.() -> Unit,
    ): HarnessRun {
        var body: BodyOutcome = BodyOutcome.NeverRan
        val teardown =
            try {
                withQuicConnection(harnessHost, QuicHarnessConfig.quicEchoPort, options, connOptions, connectTimeout) {
                    body =
                        try {
                            block()
                            BodyOutcome.Completed
                        } catch (cancellation: CancellationException) {
                            // Structured concurrency: a cancelled body is neither a failure nor a
                            // skip, and swallowing it would leave the machinery believing it ran.
                            throw cancellation
                        } catch (t: Throwable) {
                            // Recorded, not rethrown: letting it out here would run `close()` in a
                            // `finally` that can replace it. The verdict below rethrows it instead.
                            BodyOutcome.Failed(t)
                        }
                }
                Teardown.Clean
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Teardown.Threw(t)
            }
        return when (val outcome = body) {
            // The body's verdict outranks teardown's: it ran, and what it decided is the answer.
            is BodyOutcome.Failed -> HarnessRun.BodyFailed(outcome.failure)
            BodyOutcome.Completed ->
                when (teardown) {
                    Teardown.Clean -> HarnessRun.Completed
                    is Teardown.Threw -> HarnessRun.TeardownFailed(teardown.cause)
                }
            BodyOutcome.NeverRan ->
                when (teardown) {
                    is Teardown.Threw -> HarnessRun.Unreachable(teardown.cause)
                    Teardown.Clean -> HarnessRun.Unreachable(BlockNeverRan)
                }
        }
    }

    /**
     * Classify a failed establishment. An [UnsupportedOperationException] is QUIC not being
     * implemented on this platform at all (JS/Wasm) — a known, intentional gap the CI audit renders
     * as ℹ️ "platform gap (known)" — and everything else is a harness that did not answer.
     */
    private fun unreachableReason(cause: Throwable): SkipReason =
        if (cause is UnsupportedOperationException) {
            SkipReason.TransportUnavailable("QUIC is not implemented on this platform: ${cause.message}")
        } else {
            SkipReason.HarnessUnreachableFromDevice(
                "$harnessHost:${QuicHarnessConfig.quicEchoPort} did not complete a QUIC handshake. Tried by: " +
                    "this test process, to the out-of-process quic-echo peer the docker-compose harness " +
                    "publishes on the loopback of the same host (started by `harnessUp`; never started on a " +
                    "lane with HARNESS_DISABLED=true). Cause: ${cause::class.simpleName}: ${cause.message}",
            )
        }

    /** The line the CI interop audit greps; derived from the same throwable the skip reason is. */
    private fun auditMarker(cause: Throwable): String =
        if (cause is UnsupportedOperationException) {
            "platform unsupported: ${cause.message}"
        } else {
            "${cause::class.simpleName}: ${cause.message}"
        }

    /**
     * Round-trips one unreliable datagram through the echo peer. Loopback with no
     * impairment doesn't drop a single datagram, so the echo assertion is
     * deterministic (same rationale as [QuicDatagramTestSuite]).
     */
    @Test
    fun harness_datagram_roundTrip() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarnessDatagrams {
                    assertTrue(datagramChannel().maxWritableSize > 0, "datagrams should be sendable")

                    val payload = "hello dgram"
                    bufferFactory.allocate(payload.length).use { buf ->
                        buf.writeString(payload, Charset.UTF8)
                        buf.resetForRead()
                        datagramChannel().send(buf)
                    }

                    when (val r = withTimeoutOrNull(opTimeout) { datagramChannel().receive() }) {
                        is DatagramReadResult.Received -> {
                            val echo = r.datagram.payload
                            assertEquals(payload, echo.readString(echo.remaining(), Charset.UTF8))
                            echo.freeIfNeeded()
                        }
                        is DatagramReadResult.Closed ->
                            throw AssertionError("connection closed before datagram echo")
                        null -> throw AssertionError("datagram echo timed out")
                    }
                }
            }
        }

    /**
     * Accepts a SERVER-initiated stream (issue #112). The echo peer opens one stream toward
     * the client and writes a fixed greeting, exercising the path where peer-initiated
     * streams reach [QuicScope.acceptStream] — what an HTTP/3 client needs for the server's
     * control + QPACK unidirectional streams. On Apple this is the group new-connection
     * handler; on quiche it's the driver's incoming-stream surface.
     */
    @Test
    fun harness_acceptStream_receivesServerInitiatedStream() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withHarness {
                    val stream =
                        withTimeoutOrNull(opTimeout) { acceptStream() }
                            ?: throw AssertionError("no server-initiated stream arrived")
                    val result = withTimeoutOrNull(opTimeout) { stream.read(opTimeout) }
                    if (result !is ReadResult.Data) throw AssertionError("expected data on the server-initiated stream, got $result")
                    val text = result.buffer.readString(result.buffer.remaining(), Charset.UTF8)
                    result.buffer.freeIfNeeded()
                    assertEquals("HELLO\n", text)
                    stream.close()
                }
            }
        }
}
