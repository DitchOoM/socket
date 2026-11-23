package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.NssKeyLog
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.TraceDecryption
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.hexToBytes
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A core connection leaves the TLS secrets that decrypt it, in the NSS key log format Wireshark reads, where its capture
 * names a [TrafficSecretsLog.File] and where the `QUIC_KEYLOG_DIR` door names a directory; one that names neither leaves
 * nothing, and one whose file cannot be opened carries on and says so in its trace.
 */
class CoreTrafficSecretsLogTests {
    private class Recording : TraceSink {
        private val recorded = ConcurrentLinkedQueue<TraceEvent>()

        val events: List<TraceEvent> get() = recorded.toList()

        override fun emit(event: TraceEvent) {
            recorded.add(event)
        }
    }

    private class Echoed(
        val session: String,
        val reply: String,
    )

    /** One client connection to a core server that echoes [PAYLOAD]; both connections are freed when this returns. */
    private suspend fun echoOnce(capture: QuicConnectionCapture?): Echoed {
        val options =
            if (capture ==
                null
            ) {
                coreTestOptions
            } else {
                coreTestOptions.copy(trace = QuicTraceCapture(captureFor = { capture }, qlog = QlogDeclaration.NotBudgeted))
            }
        val accepted = CompletableDeferred<CoreConnection>()
        var session = ""
        var reply = ""
        withCoreEchoServer(onAccepted = { accepted.complete(it) }) { server ->
            val client = connectCore(server.port, options = options)
            session = client.connection.identity.session.hex
            val stream = client.connection.openStream()
            val out = BufferFactory.network().allocate(PAYLOAD.length)
            out.writeString(PAYLOAD, Charset.UTF8)
            out.resetForRead()
            stream.write(out, 10.seconds)
            val back = stream.read(10.seconds)
            if (back !is ReadResult.Data) fail("no echo: $back")
            reply = back.buffer.readString(back.buffer.remaining(), Charset.UTF8)
            back.buffer.freeIfNeeded()
            client.connection.close(QuicError.NoError)
            freed(client.connection)
        }
        freed(accepted.await())
        return Echoed(session, reply)
    }

    /** quiche flushes its files when it frees the connection, which the connection's scope ends after. */
    private suspend fun freed(connection: CoreConnection) = withTimeout(10.seconds) { connection.coroutineContext.job.join() }

    private fun newDirectory(): File = Files.createTempDirectory("core-keylog").toFile()

    private fun File.filesByName(): Map<String, String> = listFiles { f -> f.isFile }.orEmpty().associate { it.name to it.readText() }

    private val required =
        listOf("CLIENT_HANDSHAKE_TRAFFIC_SECRET", "SERVER_HANDSHAKE_TRAFFIC_SECRET", "CLIENT_TRAFFIC_SECRET_0", "SERVER_TRAFFIC_SECRET_0")

    @Test
    fun aNamedTrafficSecretsLogHoldsTheConnectionsTlsSecretsInNssFormat() =
        runBlocking {
            skipOnMissingNativeLib(CoreTrafficSecretsLogTests::class) {
                val dir = newDirectory()
                val sink = Recording()
                val echo = echoOnce(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File("$dir/conn-0001.keys")))
                assertEquals(PAYLOAD, echo.reply)
                val keyLog = dir.filesByName()["conn-0001.keys"] ?: fail("no key log; the directory holds ${dir.filesByName().keys}")
                val entries = NssKeyLog.parse(keyLog)
                assertTrue(entries.map { it.label }.containsAll(required), "key log carries ${entries.map { it.label }}:\n$keyLog")
                assertEquals(1, entries.map { it.clientRandom }.toSet().size, "one client random:\n$keyLog")
                assertTrue(sink.events.none { it is TraceEvent.TrafficSecretsRefused }, "an opened key log is not refused")
            }
        }

    @Test
    fun noTrafficSecretsAreWrittenUnlessAFileIsNamed() =
        runBlocking {
            skipOnMissingNativeLib(CoreTrafficSecretsLogTests::class) {
                val dir = newDirectory()
                val capture = QuicConnectionCapture(Recording(), qlog = QlogTarget.File("$dir/conn-0001.sqlog"))
                assertEquals(TrafficSecretsLog.Off, capture.trafficSecrets)
                assertEquals(PAYLOAD, echoOnce(capture).reply)
                val files = dir.filesByName()
                assertEquals(setOf("conn-0001.sqlog"), files.keys)
                assertTrue(files.values.none { body -> required.any { it in body } }, "no file may carry TLS secrets: ${files.keys}")
            }
        }

    @Test
    fun aKeyLogThatCannotBeOpenedIsReportedInTheTraceAndTheConnectionCarriesOn() =
        runBlocking {
            skipOnMissingNativeLib(CoreTrafficSecretsLogTests::class) {
                val path = "${newDirectory()}/no-such-directory/conn-0001.keys"
                val sink = Recording()
                val echo = echoOnce(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File(path)))
                assertEquals(PAYLOAD, echo.reply, "a key log that cannot be opened must never cost the connection")
                assertEquals(listOf(path), sink.events.filterIsInstance<TraceEvent.TrafficSecretsRefused>().map { it.path })
            }
        }

    @Test
    fun theEnvironmentDoorWritesBothEndsKeyLogsNamedBySessionAndTheyAgree() =
        runBlocking {
            skipOnMissingNativeLib(CoreTrafficSecretsLogTests::class) {
                val dir = newDirectory()
                val echo = withKeyLogDir(dir) { echoOnce(null) }
                assertEquals(PAYLOAD, echo.reply)
                val files = dir.filesByName()
                val client = files["quiche-client-${echo.session}.keys"] ?: fail("no client key log; the directory holds ${files.keys}")
                val server =
                    files.filterKeys { it.startsWith("quiche-server-") && it.endsWith(".keys") }.values.singleOrNull()
                        ?: fail("no single server key log; the directory holds ${files.keys}")
                val clientEntries = NssKeyLog.parse(client).filter { it.label in required }.toSet()
                val serverEntries = NssKeyLog.parse(server).filter { it.label in required }.toSet()
                assertEquals(required.toSet(), clientEntries.map { it.label }.toSet(), "client key log:\n$client")
                assertEquals(clientEntries, serverEntries, "both ends derive the same secrets:\nclient:\n$client\nserver:\n$server")
            }
        }

    /** The point of a key log: every datagram the trace recorded opens with the secrets in the file. */
    @Test
    fun theKeyLogDecryptsEveryPacketTheTraceRecorded() =
        runBlocking {
            skipOnMissingNativeLib(CoreTrafficSecretsLogTests::class) {
                val dir = newDirectory()
                val sink = Recording()
                val echo = echoOnce(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File("$dir/conn-0001.keys")))
                assertEquals(PAYLOAD, echo.reply)
                val keyLog = dir.filesByName()["conn-0001.keys"] ?: fail("no key log; the directory holds ${dir.filesByName().keys}")
                val secrets = NssKeyLog.parse(keyLog).associate { it.label to it.secret.hexToBytes() }
                val sent = sink.events.filterIsInstance<TraceEvent.DgramOut>().map { it.payloadHex.hexToBytes() }
                val received = sink.events.filterIsInstance<TraceEvent.DgramIn>().map { it.payloadHex.hexToBytes() }
                assertTrue(sent.isNotEmpty() && received.isNotEmpty(), "no datagrams recorded: ${sent.size} out, ${received.size} in")
                val report = TraceDecryption(sent, received, secrets, PAYLOAD).run()
                assertEquals(NssKeyLog.parse(keyLog).map { it.clientRandom }.toSet(), setOf(report.clientHelloRandom))
                assertTrue(report.failures.isEmpty(), "packets the key log did not open:\n${report.failures.joinToString("\n")}\n$report")
                for (space in listOf("Initial", "Handshake", "1-RTT")) {
                    assertTrue((report.opened["client $space"] ?: 0) > 0, "no client $space packet was opened: $report")
                    assertTrue((report.opened["server $space"] ?: 0) > 0, "no server $space packet was opened: $report")
                }
                assertTrue(
                    report.clientPlaintextHasPayload && report.serverPlaintextHasPayload,
                    "the payload is in both 1-RTT plaintexts: $report",
                )
            }
        }

    /** The JVM's `QUIC_KEYLOG_DIR` seam: a process cannot set its own environment, the property is read first. */
    private inline fun <T> withKeyLogDir(
        dir: File,
        block: () -> T,
    ): T {
        val previous = System.getProperty(KEYLOG_DIR_PROPERTY)
        System.setProperty(KEYLOG_DIR_PROPERTY, dir.absolutePath)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(KEYLOG_DIR_PROPERTY) else System.setProperty(KEYLOG_DIR_PROPERTY, previous)
        }
    }

    private companion object {
        const val PAYLOAD = "decrypt me offline"
        const val KEYLOG_DIR_PROPERTY = "quic.keylog.dir"
    }
}
