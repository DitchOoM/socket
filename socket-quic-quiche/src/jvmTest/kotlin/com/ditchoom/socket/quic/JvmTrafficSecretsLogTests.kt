package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * JVM member of [TrafficSecretsLogTestSuite], plus the two things only the JVM can check here: the
 * `quic.keylog.dir` environment door, and that the key log actually decrypts what the trace recorded.
 */
class JvmTrafficSecretsLogTests : TrafficSecretsLogTestSuite() {
    private fun certPath(name: String): String {
        val url = this::class.java.classLoader.getResource("certs/$name") ?: error("Test cert not found: certs/$name")
        return File(url.toURI()).absolutePath
    }

    override fun testTlsConfig() = QuicTlsConfig(certChainPath = certPath("cert.crt"), privKeyPath = certPath("cert.key"))

    override suspend fun wrapTestBody(block: suspend () -> Unit) = skipOnMissingNativeLib(JvmTrafficSecretsLogTests::class, block)

    override fun newDirectory(): String = Files.createTempDirectory("traffic-secrets").toFile().absolutePath

    override fun filesIn(dir: String): Map<String, String> =
        File(dir)
            .listFiles { f -> f.isFile }
            .orEmpty()
            .associate { it.name to it.readText() }

    /** The JVM's `QUIC_KEYLOG_DIR` seam: a process cannot set its own environment, the property is read first. */
    private suspend fun <T> withKeyLogDir(
        dir: String,
        block: suspend () -> T,
    ): T {
        val previous = System.getProperty(KEYLOG_DIR_PROPERTY)
        System.setProperty(KEYLOG_DIR_PROPERTY, dir)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(KEYLOG_DIR_PROPERTY) else System.setProperty(KEYLOG_DIR_PROPERTY, previous)
        }
    }

    @Test
    fun theEnvironmentDoorWritesBothEndsKeyLogsNamedBySessionAndTheyAgree() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val dir = newDirectory()
                val echo = withKeyLogDir(dir) { echoOnce(serverOptions) }
                assertEquals(ECHO_PAYLOAD, echo.reply, "the connection under test must work before its key log means anything")

                val files = filesIn(dir)
                val client =
                    files["quiche-client-${echo.session}.keys"]
                        ?: throw AssertionError(
                            "the client's key log is named by its session ${echo.session}; the directory holds ${files.keys}",
                        )
                val server =
                    files.filterKeys { it.startsWith("quiche-server-") && it.endsWith(".keys") }.values.singleOrNull()
                        ?: throw AssertionError(
                            "the accepted connection writes its own key log beside the client's; the directory holds ${files.keys}",
                        )
                val clientEntries = NssKeyLog.parse(client).filter { it.label in REQUIRED_LABELS }.toSet()
                val serverEntries = NssKeyLog.parse(server).filter { it.label in REQUIRED_LABELS }.toSet()
                assertEquals(REQUIRED_LABELS.toSet(), clientEntries.map { it.label }.toSet(), "client key log:\n$client")
                assertEquals(
                    clientEntries,
                    serverEntries,
                    "two endpoints of one connection derive the same secrets for the same client random; they disagree:\n" +
                        "client:\n$client\nserver:\n$server",
                )
            }
        }

    /**
     * The point of a key log: every packet the trace recorded, in both directions and every packet number
     * space, opens with the secrets in the file, using code written against RFC 9001 and nothing of quiche's.
     */
    @Test
    fun theKeyLogDecryptsEveryPacketTheTraceRecorded() =
        runQuicTest(timeout = 30.seconds) {
            wrapTestBody {
                val dir = newDirectory()
                val sink = RecordingSink()
                val echo = echoOnce(capturing(QuicConnectionCapture(sink, trafficSecrets = TrafficSecretsLog.File("$dir/conn-0001.keys"))))
                assertEquals(ECHO_PAYLOAD, echo.reply, "the connection under test must work before its key log means anything")
                val keyLog =
                    filesIn(dir)["conn-0001.keys"]
                        ?: throw AssertionError("no key log was written; the directory holds ${filesIn(dir).keys}")
                val secrets = NssKeyLog.parse(keyLog).associate { it.label to it.secret.hexToBytes() }

                val sent = sink.events.filterIsInstance<TraceEvent.DgramOut>().map { it.payloadHex.hexToBytes() }
                val received = sink.events.filterIsInstance<TraceEvent.DgramIn>().map { it.payloadHex.hexToBytes() }
                val report = TraceDecryption(sent, received, secrets, ECHO_PAYLOAD).run()
                println("[keylog-decrypt] $report")

                assertEquals(
                    NssKeyLog.parse(keyLog).map { it.clientRandom }.toSet(),
                    setOf(report.clientHelloRandom),
                    "the key log is keyed by the client random of the ClientHello this connection sent",
                )
                assertTrue(report.failures.isEmpty(), "packets the key log did not open:\n${report.failures.joinToString("\n")}\n$report")
                for (space in listOf("Initial", "Handshake", "1-RTT")) {
                    assertTrue((report.opened["client $space"] ?: 0) > 0, "no client $space packet was opened: $report")
                    assertTrue((report.opened["server $space"] ?: 0) > 0, "no server $space packet was opened: $report")
                }
                assertTrue(report.clientPlaintextHasPayload, "the client's 1-RTT plaintext must carry \"$ECHO_PAYLOAD\": $report")
                assertTrue(
                    report.serverPlaintextHasPayload,
                    "the server's 1-RTT plaintext must carry the echo of \"$ECHO_PAYLOAD\": $report",
                )
            }
        }

    private companion object {
        const val KEYLOG_DIR_PROPERTY = "quic.keylog.dir"
    }
}
