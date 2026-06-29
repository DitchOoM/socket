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
 * doesn't advertise WebTransport, draft-version mismatch on the CONNECT) is caught and logged as a SKIP.
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

    @Test
    fun echoesOverPublicWebTransport_orSkips() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val payload = "ditchoom-webtransport-interop"
                var validated = 0
                for ((host, port, path) in endpoints) {
                    val echoed: String? =
                        try {
                            withHttp3Connection(
                                host,
                                port,
                                quicOptions,
                                connectionOptions,
                                timeout = 8.seconds,
                                webTransport = WebTransportOptions(maxSessions = 1),
                            ) {
                                withTimeout(5.seconds) { peerSettings() }
                                val session = connectWebTransport(authority = host, path = path)
                                try {
                                    val stream = session.openBidiStream()
                                    val out = textBuffer(payload)
                                    try {
                                        stream.write(out)
                                    } finally {
                                        out.freeIfNeeded() // write is zero-copy; it does not take ownership
                                    }
                                    stream.shutdownSend()
                                    withTimeout(8.seconds) { stream.drainUtf8() }
                                } finally {
                                    session.close(code = 0, reason = "interop done")
                                }
                            }
                        } catch (t: Throwable) {
                            println(
                                "[WebTransportPublicEndpointInteropTests] public WT SKIP $host:$port$path — " +
                                    "${t::class.simpleName}: ${t.message}",
                            )
                            null
                        }

                    if (echoed != null) {
                        // A real, foreign WebTransport echo completed — the bytes MUST match exactly.
                        assertEquals(payload, echoed, "$host: WebTransport bidi echo must round-trip unchanged")
                        validated++
                        println("[WebTransportPublicEndpointInteropTests] public WT OK $host:$port$path echo=\"$echoed\"")
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
