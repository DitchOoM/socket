package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.socket.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for #423: a read issued before the first write on a freshly opened stream.
 *
 * ## What was wrong
 *
 * `openStream()` only reserved a stream id locally — quiche was never told the stream existed, because
 * a QUIC stream becomes real to the library on its first `stream_send`. So a read before that write hit
 * `QUICHE_ERR_INVALID_STREAM_STATE`, and the caller was told the stream had *finished*. It had not
 * started.
 *
 * #421 later stopped that code being laundered into a clean `ReadResult.End`, which was the right fix
 * for real transport errors and the wrong answer here: it traded "the stream is over" for "the stream
 * failed", and a caller reading from a stream it just opened has done nothing wrong. `openStream()` now
 * materialises the stream with quiche, so a read before the first write is simply a read with nothing
 * available yet — which is what the method's name always implied.
 *
 * ## Why the second test is the one that matters
 *
 * [aReadBeforeTheFirstWriteIsNotAFailure] pins the narrow symptom. But the reason the defect mattered is
 * ordinary: **starting a reader before sending the request is a completely normal shape** — a receive
 * loop launched when the stream is created, a request written afterwards — and it simply did not work.
 * [aReaderStartedBeforeTheFirstWriteStillReceivesTheEcho] is that pattern end to end against a real
 * quiche server, and it names no internal function, so no stub or call-count can satisfy it: either the
 * bytes come back or they do not.
 *
 * Both run against a real loopback QUIC server, not a double. A stub asserting "openStream now calls
 * stream_send with zero bytes" would be the classic decorative test — it would pass against an
 * implementation that made the call and achieved nothing.
 */
class OpenStreamReadBeforeWriteTests {
    private val testQuicOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    private fun certPath(name: String): String {
        val url =
            this::class.java.classLoader.getResource("certs/$name")
                ?: error("Test cert not found: certs/$name")
        return java.io.File(url.toURI()).absolutePath
    }

    private val tlsConfig
        get() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    /**
     * A read on a stream nobody has written to yet must not report a failure.
     *
     * There is genuinely nothing to read: the peer cannot have sent anything on a client-initiated
     * stream it has not been told about. "Nothing available yet" is a deadline expiring, not an error
     * and not an end-of-stream — so the read is expected to time out, and specifically NOT to raise
     * [QuicStreamReadException] with [QuicStreamReadError.InvalidStreamState].
     */
    @Test
    fun aReadBeforeTheFirstWriteIsNotAFailure() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(OpenStreamReadBeforeWriteTests::class) {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverJob = launch(Dispatchers.IO) { connections { /* accept and hold */ } }
                        try {
                            withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                val stream = openStream()
                                val outcome =
                                    try {
                                        Read(stream.read(1.seconds))
                                    } catch (e: QuicStreamReadException) {
                                        Threw(e)
                                    } catch (e: TimeoutCancellationException) {
                                        // The pre-existing stream-read deadline contract: a read with
                                        // nothing to deliver propagates the timeout. Not changed here —
                                        // #423 is about not calling "not started yet" a failure.
                                        TimedOut
                                    } catch (e: SocketTimeoutException) {
                                        TimedOut
                                    }

                                when (outcome) {
                                    is Threw ->
                                        fail(
                                            "reading a freshly opened stream reported a transport failure " +
                                                "(#423): ${outcome.e.error}. The stream has not finished and " +
                                                "it has not failed — it has not started. openStream() only " +
                                                "reserved a stream id locally, so quiche did not know the " +
                                                "stream existed and answered INVALID_STREAM_STATE. A caller " +
                                                "reading from a stream it just opened has done nothing wrong.",
                                        )
                                    is Read ->
                                        assertTrue(
                                            outcome.result !is ReadResult.End,
                                            "a read before the first write must not report end-of-stream " +
                                                "(#423): nothing has closed this stream, and reporting End " +
                                                "tells the caller to stop reading and release it.",
                                        )
                                    TimedOut -> Unit // correct: nothing available yet
                                }
                            }
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /**
     * The shape the defect actually broke: a reader started before the request is written.
     *
     * The read is launched first and is therefore in flight before anything has been sent on the
     * stream — exactly the ordering that used to fail — and must still receive the echo.
     */
    @Test
    fun aReaderStartedBeforeTheFirstWriteStillReceivesTheEcho() =
        runBlocking(Dispatchers.IO) {
            skipOnMissingNativeLib(OpenStreamReadBeforeWriteTests::class) {
                withTimeout(20.seconds) {
                    withQuicServer(port = 0, tlsConfig = tlsConfig, quicOptions = testQuicOptions) {
                        val serverReady = CompletableDeferred<Unit>()
                        val serverJob =
                            launch(Dispatchers.IO) {
                                connections {
                                    serverReady.complete(Unit)
                                    val stream = acceptStream()
                                    val data = stream.read(10.seconds)
                                    if (data is ReadResult.Data) stream.write(data.buffer, 10.seconds)
                                    stream.close()
                                }
                            }
                        try {
                            val echoed =
                                withQuicConnection("localhost", port, testQuicOptions, timeout = 10.seconds) {
                                    val stream = openStream()

                                    // Reader first, writer second — the ordering #423 broke.
                                    val reader = async(Dispatchers.IO) { stream.read(10.seconds) }

                                    val payload = "read-before-write"
                                    val buf = BufferFactory.Default.allocate(payload.length)
                                    buf.writeString(payload, Charset.UTF8)
                                    buf.resetForRead()
                                    stream.write(buf, 10.seconds)

                                    val response = reader.await()
                                    if (response is ReadResult.Data) {
                                        response.buffer.readString(response.buffer.remaining(), Charset.UTF8)
                                    } else {
                                        fail(
                                            "the reader started before the first write got $response instead of " +
                                                "the echo (#423): a receive loop launched when the stream is " +
                                                "created is an ordinary shape and must work.",
                                        )
                                    }
                                }
                            assertEquals("read-before-write", echoed, "the echo must round-trip intact")
                        } finally {
                            serverJob.cancel()
                        }
                    }
                }
            }
        }

    /** What a read attempt did, so the assertion can name it rather than just tripping. */
    private sealed interface ReadOutcome

    private data class Read(
        val result: ReadResult,
    ) : ReadOutcome

    private data class Threw(
        val e: QuicStreamReadException,
    ) : ReadOutcome

    private data object TimedOut : ReadOutcome
}
