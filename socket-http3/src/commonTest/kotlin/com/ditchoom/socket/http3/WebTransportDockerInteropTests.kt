package com.ditchoom.socket.http3

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.ConnectionOptions
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
 * **WebTransport interop** against an INDEPENDENT stack (aioquic) — the reliable, CI-runnable counterpart
 * to [WebTransportPublicEndpointInteropTests] (which has no reachable public WT echo server). The
 * aioquic server in `socket-http3/docker-interop/` accepts a WebTransport Extended CONNECT and echoes
 * each bidirectional stream byte-for-byte; this test opens a session, opens a bidi stream, sends a
 * payload, half-closes, and asserts the echo round-trips unchanged.
 *
 * Why a foreign stack: the in-process [Http3LoopbackTestSuite] WebTransport cases run our client against
 * our OWN server, so a bug symmetric across both halves hides. aioquic also pins a real interop
 * subtlety — it advertises only the legacy draft-02 `ENABLE_WEBTRANSPORT` setting, not the newer
 * `WEBTRANSPORT_MAX_SESSIONS`. This test is the regression guard for our client accepting that legacy
 * signal (see [Http3Settings.webTransportSupported]); without it, `connectWebTransport` would reject the
 * peer and this test would fail rather than echo.
 *
 * **Skip-on-unreachable.** If the docker server isn't up on 127.0.0.1:4433 the handshake fails before
 * [connected] flips and the test logs a SKIP — never a flaky failure. Once the H3 connection is
 * established, every step (session, stream echo) is a real assertion: this is OUR controlled peer, so
 * "reachable but WebTransport misbehaves" is exactly the regression we want surfaced. Start the server
 * with `socket-http3/docker-interop/run-server.sh start`; CI does this in build-linux.yaml.
 */
class WebTransportDockerInteropTests {
    private val host = "127.0.0.1"
    private val port = 4433

    private val quicOptions =
        QuicOptions(
            alpnProtocols = listOf(HTTP3_ALPN),
            verifyPeer = false, // self-signed docker cert; identity doesn't matter for interop
            idleTimeout = 10.seconds,
            datagrams = DatagramOptions(), // WebTransport requires H3 datagrams negotiated in the handshake
        )
    private val connectionOptions = ConnectionOptions(bufferFactory = BufferFactory.deterministic())

    @Test
    fun bidiStreamEchoesThroughAioquicWebTransport_orSkips() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val payload = "ditchoom-wt-docker-interop"
                var connected = false
                val echoed: String? =
                    try {
                        // `timeout` caps the whole operation (handshake + session + echo + teardown), so
                        // give it headroom for CI/full-suite contention; a down server still fails fast.
                        withHttp3Connection(
                            host,
                            port,
                            quicOptions,
                            connectionOptions,
                            timeout = 20.seconds,
                            webTransport = WebTransportOptions(maxSessions = 1),
                        ) {
                            withTimeout(10.seconds) { peerSettings() }
                            connected = true // H3 connection is up — anything past here is a real check
                            val session = connectWebTransport(authority = "localhost", path = "/")
                            try {
                                val stream = session.openBidiStream()
                                val out = textBuffer(payload)
                                try {
                                    stream.write(out)
                                } finally {
                                    out.freeIfNeeded() // write is zero-copy; it does not take ownership
                                }
                                stream.shutdownSend()
                                withTimeout(15.seconds) { stream.drainUtf8() }
                            } finally {
                                session.close(code = 0, reason = "interop done")
                            }
                        }
                    } catch (t: Throwable) {
                        if (connected) throw t // failure after a live connection is a real regression
                        println(
                            "[WebTransportDockerInteropTests] SKIP — aioquic WT server not reachable on " +
                                "$host:$port (start it: socket-http3/docker-interop/run-server.sh start) — " +
                                "${t::class.simpleName}: ${t.message}",
                        )
                        null
                    }

                if (echoed != null) {
                    assertEquals(payload, echoed, "aioquic WebTransport bidi echo must round-trip unchanged")
                    println("[WebTransportDockerInteropTests] OK — bidi stream echoed through aioquic WebTransport")
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
