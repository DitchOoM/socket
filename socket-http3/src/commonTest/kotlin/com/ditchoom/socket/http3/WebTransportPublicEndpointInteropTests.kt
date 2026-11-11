package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.QuicOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Real-world **WebTransport interop** smoke test: can our client open a WebTransport session (RFC 9220
 * Extended CONNECT over a real `h3` server), open a bidirectional stream, and get an exact echo back —
 * against a third-party WebTransport stack, not our own loopback? The in-process
 * [Http3LoopbackTestSuite] WebTransport cases round-trip our client through our *own* server, so a bug
 * symmetric across both halves could hide; a foreign server (e.g. the public `webtransport.day` echo,
 * a Go/quic-go stack) can't be wrong in the same way.
 *
 * **Skip-on-unreachable, never flaky-fail** — same contract as [Http3PublicEndpointInteropTests] and
 * [QuicPublicEndpointInteropTests]. UDP/443 egress isn't guaranteed in CI, public WebTransport servers
 * come and go, and JS has no QUIC — so any *establishment* failure (no egress, server down, peer
 * doesn't advertise WebTransport, draft-version mismatch on the CONNECT) is caught and logged as a SKIP,
 * naming the phase it stopped in (see [PublicEndpointAttempt]). An attempt still running
 * [PublicEndpointAttempt.CANCEL_GRACE] after its budget cancelled it is this library's defect, and fails.
 * The echo-equality assertion runs **outside** the skip-catch: a session that establishes and a stream
 * that round-trips but returns the WRONG bytes is a real regression and fails the test. If every
 * endpoint skips, a single loud line flags that no real WebTransport interop was validated.
 *
 * Datagrams are deliberately NOT asserted — they're unreliable by design (RFC 9221), so an echo
 * datagram may legitimately never arrive; only the ordered, reliable bidi-stream echo is the contract.
 */
class WebTransportPublicEndpointInteropTests {
    // host, port, session path. The echo server echoes whatever a bidi stream sends.
    private val endpoints =
        listOf(
            Triple("webtransport.day", 443, "/"),
        )

    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = true, // real CT-logged cert — the production path
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(), // WebTransport requires H3 datagrams in the handshake
        )
    private val connectionOptions = TransportConfig(bufferFactory = BufferFactory.deterministic())

    private companion object {
        /** The whole exchange, resolution to close. Its steps had no bound of their own past the echo. */
        val ENDPOINT_BUDGET = 30.seconds
    }

    @Test
    fun echoesOverPublicWebTransport_orSkips() =
        // Longer than every endpoint's budget plus its cancellation grace, so this test's own verdict
        // (which phase stalled, and whether it ignored cancellation) always lands before runTest's.
        runTest(timeout = (ENDPOINT_BUDGET + PublicEndpointAttempt.CANCEL_GRACE) * endpoints.size + 10.seconds) {
            withContext(Dispatchers.Default) {
                val payload = "ditchoom-webtransport-interop"
                var validated = 0
                for ((host, port, path) in endpoints) {
                    val outcome =
                        PublicEndpointAttempt.run("$host:$port$path", ENDPOINT_BUDGET) {
                            resolve(host)
                            enter("handshake")
                            withHttp3Connection(
                                host,
                                port,
                                observing(quicOptions),
                                connectionOptions,
                                timeout = 8.seconds,
                                webTransport = WebTransportOptions(maxSessions = 1),
                            ) {
                                enter("settings")
                                withTimeout(5.seconds) { peerSettings() }
                                enter("session CONNECT")
                                val session = connectWebTransport(authority = host, path = path)
                                try {
                                    enter("open stream")
                                    val stream = session.openBidiStream()
                                    val out = textBuffer(payload)
                                    try {
                                        enter("write")
                                        stream.write(out)
                                    } finally {
                                        out.freeIfNeeded() // write is zero-copy; it does not take ownership
                                    }
                                    stream.shutdownSend()
                                    enter("echo")
                                    withTimeout(8.seconds) { stream.drainUtf8() }
                                } finally {
                                    enter("session close")
                                    session.close(code = 0, reason = "interop done")
                                    enter("close")
                                }
                            }
                        }
                    when (outcome) {
                        is PublicEndpointAttempt.Outcome.Done -> {
                            // A real, foreign WebTransport echo completed — the bytes MUST match exactly.
                            assertEquals(payload, outcome.value, "$host: WebTransport bidi echo must round-trip unchanged")
                            validated++
                            println(
                                "[WebTransportPublicEndpointInteropTests] public WT OK $host:$port$path " +
                                    "echo=\"${outcome.value}\" (${outcome.report})",
                            )
                        }
                        is PublicEndpointAttempt.Outcome.Unreachable ->
                            println("[WebTransportPublicEndpointInteropTests] public WT SKIP ${outcome.report}")
                        is PublicEndpointAttempt.Outcome.IgnoredCancellation -> fail(outcome.report)
                    }
                }
                if (validated == 0) {
                    println(
                        "[WebTransportPublicEndpointInteropTests] ALL endpoints skipped — no public WebTransport " +
                            "echo validated (check UDP:443 egress / server availability / platform QUIC support)",
                    )
                }
            }
        }

    private fun textBuffer(s: String) =
        BufferFactory.deterministic().allocate(s.length.coerceAtLeast(1)).apply {
            writeString(s, Charset.UTF8)
            resetForRead()
        }
}

/** Drain a bidirectional WebTransport stream to end-of-stream as a UTF-8 string. */
private suspend fun WebTransportStream.drainUtf8(): String {
    val sb = StringBuilder()
    while (true) {
        when (val r = read(8.seconds)) {
            is ReadResult.Data -> {
                sb.append(r.buffer.readString(r.buffer.remaining(), Charset.UTF8))
                r.buffer.freeIfNeeded()
            }
            ReadResult.End, ReadResult.Reset -> return sb.toString()
        }
    }
}
