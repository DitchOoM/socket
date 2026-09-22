@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.deterministic
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A connection whose capture names a [TrafficSecretsLog.File] leaves the TLS secrets that decrypt it
 * there, in the NSS key log format Wireshark reads; one that names none leaves nothing.
 *
 * Every backend binds `quiche_conn_set_keylog_path` separately (JNI shim, FFM, two cinterop files), so
 * the suite runs on each; the JVM member additionally decrypts the recorded datagrams with the file.
 */
abstract class TrafficSecretsLogTestSuite {
    abstract fun testTlsConfig(): QuicTlsConfig

    /** Same hook as every other suite: the JVM/Android members turn a missing native into a typed skip. */
    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /** A new, empty directory, as an absolute path. */
    abstract fun newDirectory(): String

    /** Every regular file directly in [dir], by name, with its text. */
    abstract fun filesIn(dir: String): Map<String, String>

    protected val serverOptions =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 10.seconds,
        )

    /** Collects what a connection's driver recorded; lock-free because the driver and reader loops both emit. */
    protected class RecordingSink : TraceSink {
        private val recorded = AtomicReference(emptyList<TraceEvent>())

        val events: List<TraceEvent> get() = recorded.load()

        override fun emit(event: TraceEvent) {
            while (true) {
                val current = recorded.load()
                if (recorded.compareAndSet(current, current + event)) return
            }
        }
    }

    /** A client options value whose single connection is captured as [capture] describes. */
    protected fun capturing(capture: QuicConnectionCapture): QuicOptions =
        serverOptions.copy(trace = QuicTraceCapture(captureFor = { capture }))

    /**
     * One client connection that echoes [ECHO_PAYLOAD] over a stream, so both directions carry 1-RTT
     * packets under the application secrets. Returns the connection's session id and the echo.
     */
    protected suspend fun echoOnce(clientOptions: QuicOptions): Echo =
        withQuicServer(port = 0, tlsConfig = testTlsConfig(), quicOptions = serverOptions) {
            coroutineScope {
                val serverJob =
                    launch {
                        connections {
                            val stream = acceptStream()
                            // Echo inside the read scope: write takes no ownership, the scope frees the buffer.
                            stream.read(5.seconds) { stream.write(it, 5.seconds) }
                            stream.close()
                        }
                    }
                try {
                    withQuicConnection("127.0.0.1", port, clientOptions, timeout = 10.seconds) {
                        val stream = openStream()
                        val out = BufferFactory.deterministic().allocate(ECHO_PAYLOAD.length)
                        out.writeString(ECHO_PAYLOAD, Charset.UTF8)
                        out.resetForRead()
                        stream.write(out, 5.seconds)
                        val reply = stream.read(5.seconds) { it.readString(it.remaining(), Charset.UTF8) }
                        stream.close()
                        Echo(identity.session.hex, if (reply is ScopedRead.Data) reply.value else "no data: $reply")
                    }
                } finally {
                    serverJob.cancel()
                }
            }
        }

    protected data class Echo(
        val session: String,
        val reply: String,
    )

    @Test
    fun aNamedTrafficSecretsLogHoldsTheConnectionsTlsSecretsInNssFormat() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val dir = newDirectory()
                val sink = RecordingSink()
                val echo = echoOnce(capturing(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File("$dir/conn-0001.keys"))))
                assertEquals(ECHO_PAYLOAD, echo.reply, "the connection under test must work before its key log means anything")

                val files = filesIn(dir)
                val keyLog =
                    files["conn-0001.keys"]
                        ?: throw AssertionError(
                            "a capture naming TrafficSecretsLog.File(\"$dir/conn-0001.keys\") left no key log; the " +
                                "directory holds ${files.keys}. Trace refusals: ${sink.events.filterIsInstance<TraceEvent.TrafficSecretsRefused>()}",
                        )
                val entries = NssKeyLog.parse(keyLog)
                assertTrue(
                    entries.map { it.label }.containsAll(REQUIRED_LABELS),
                    "the key log must carry every secret a Handshake and 1-RTT decryption needs, $REQUIRED_LABELS; " +
                        "it carries ${entries.map { it.label }}:\n$keyLog",
                )
                assertEquals(
                    1,
                    entries.map { it.clientRandom }.toSet().size,
                    "one connection's secrets are keyed by its one client random:\n$keyLog",
                )
                assertTrue(
                    sink.events.none { it is TraceEvent.TrafficSecretsRefused },
                    "an opened key log must not also be reported refused: ${sink.events.filterIsInstance<TraceEvent.TrafficSecretsRefused>()}",
                )
            }
        }

    @Test
    fun noTrafficSecretsAreWrittenUnlessAFileIsNamed() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val dir = newDirectory()
                // The probes' shape before a key log existed: a trace and a qlog, nothing else named.
                val capture = QuicConnectionCapture(RecordingSink(), qlog = QlogTarget.File("$dir/conn-0001.sqlog"))
                assertEquals(TrafficSecretsLog.Off, capture.trafficSecrets, "a capture that names no key log writes none")
                val echo = echoOnce(capturing(capture))
                assertEquals(ECHO_PAYLOAD, echo.reply, "the connection under test must work, or it wrote nothing for a different reason")

                val files = filesIn(dir)
                assertEquals(setOf("conn-0001.sqlog"), files.keys, "only the qlog that was named may appear beside the trace")
                val leaked = files.filterValues { body -> REQUIRED_LABELS.any { it in body } }.keys
                assertTrue(leaked.isEmpty(), "no file may carry TLS secrets when no key log was named: $leaked")
            }
        }

    @Test
    fun aKeyLogThatCannotBeOpenedIsReportedInTheTraceAndTheConnectionCarriesOn() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val path = "${newDirectory()}/no-such-directory/conn-0001.keys"
                val sink = RecordingSink()
                val echo = echoOnce(capturing(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File(path))))
                assertEquals(ECHO_PAYLOAD, echo.reply, "a key log that cannot be opened must never cost the connection")
                assertEquals(
                    listOf(path),
                    sink.events.filterIsInstance<TraceEvent.TrafficSecretsRefused>().map { it.path },
                    "the walk's own trace is where a missing key log must show up; stdout alone never reaches the walk log",
                )
            }
        }

    protected companion object {
        const val ECHO_PAYLOAD = "decrypt me offline"

        val REQUIRED_LABELS =
            listOf(
                "CLIENT_HANDSHAKE_TRAFFIC_SECRET",
                "SERVER_HANDSHAKE_TRAFFIC_SECRET",
                "CLIENT_TRAFFIC_SECRET_0",
                "SERVER_TRAFFIC_SECRET_0",
            )
    }
}

/** One line of an NSS key log: `<label> <client random, hex> <secret, hex>`. */
data class NssKeyLogEntry(
    val label: String,
    val clientRandom: String,
    val secret: String,
)

object NssKeyLog {
    private val line = Regex("^([A-Z0-9_]+) ([0-9a-f]{64}) ([0-9a-f]+)$")

    /** Every entry in [text]; a line that is not NSS key log format fails loudly with the whole file. */
    fun parse(text: String): List<NssKeyLogEntry> =
        text.lines().filter { it.isNotEmpty() }.map { raw ->
            val match = line.matchEntire(raw) ?: throw AssertionError("not an NSS key log line: \"$raw\" in:\n$text")
            val (label, random, secret) = match.destructured
            NssKeyLogEntry(label, random, secret)
        }
}
