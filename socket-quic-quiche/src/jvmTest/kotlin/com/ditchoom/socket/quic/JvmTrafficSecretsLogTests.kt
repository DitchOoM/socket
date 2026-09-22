package com.ditchoom.socket.quic

import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.TrafficSecretsLog
import com.ditchoom.socket.testkit.trace.TraceEvent
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
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
                val report = TraceDecryption(sent, received, secrets).run()
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

    /** Opens a client-vantage trace's datagrams with a key log's secrets, packet by packet. */
    private class TraceDecryption(
        private val sent: List<ByteArray>,
        private val received: List<ByteArray>,
        private val secrets: Map<String, ByteArray>,
    ) {
        data class Report(
            val clientHelloRandom: String,
            val suite: QuicPacketOpener.Suite,
            val opened: Map<String, Int>,
            val failures: List<String>,
            val clientPlaintextHasPayload: Boolean,
            val serverPlaintextHasPayload: Boolean,
        )

        private val opened = mutableMapOf<String, Int>()
        private val failures = mutableListOf<String>()
        private val largest = mutableMapOf<String, Long>()
        private val payload = ECHO_PAYLOAD.encodeToByteArray()

        fun run(): Report {
            val clientInitial = QuicPacketOpener.locate(sent.first(), 0).first() as QuicPacketOpener.Located.Long
            val (clientInitialKeys, serverInitialKeys) = QuicPacketOpener.initialKeys(clientInitial.dcid)
            val clientHello =
                QuicPacketOpener.cryptoData(QuicPacketOpener.open(sent.first(), clientInitial, clientInitialKeys, -1).payload)
            // Each side's short-header DCID is the SCID the other side chose in its long headers.
            val (serverDatagram, serverInitial) =
                received.firstNotNullOf { datagram ->
                    (QuicPacketOpener.locate(datagram, 0).firstOrNull() as? QuicPacketOpener.Located.Long)?.let { datagram to it }
                }
            val serverHello =
                QuicPacketOpener.cryptoData(QuicPacketOpener.open(serverDatagram, serverInitial, serverInitialKeys, -1).payload)
            val suite = QuicPacketOpener.serverHelloSuite(serverHello)

            fun keysFor(label: String) = QuicPacketOpener.keys(suite, secrets[label] ?: throw AssertionError("the key log has no $label"))
            val client =
                mapOf(
                    "Initial" to clientInitialKeys,
                    "Handshake" to keysFor("CLIENT_HANDSHAKE_TRAFFIC_SECRET"),
                    "1-RTT" to keysFor("CLIENT_TRAFFIC_SECRET_0"),
                )
            val server =
                mapOf(
                    "Initial" to serverInitialKeys,
                    "Handshake" to keysFor("SERVER_HANDSHAKE_TRAFFIC_SECRET"),
                    "1-RTT" to keysFor("SERVER_TRAFFIC_SECRET_0"),
                )
            val clientHasPayload = openAll("client", sent, serverInitial.scid.size, client)
            val serverHasPayload = openAll("server", received, clientInitial.scid.size, server)
            return Report(
                clientHelloRandom = QuicPacketOpener.helloRandom(clientHello).toHexString(),
                suite = suite,
                opened = opened.toMap(),
                failures = failures.toList(),
                clientPlaintextHasPayload = clientHasPayload,
                serverPlaintextHasPayload = serverHasPayload,
            )
        }

        /** Opens every packet in [datagrams]; true when a 1-RTT plaintext carried [payload]. */
        private fun openAll(
            sender: String,
            datagrams: List<ByteArray>,
            shortDcidLength: Int,
            keys: Map<String, QuicPacketOpener.PacketKeys>,
        ): Boolean {
            var sawPayload = false
            datagrams.forEachIndexed { index, datagram ->
                for (packet in QuicPacketOpener.locate(datagram, shortDcidLength)) {
                    val space =
                        when (packet) {
                            is QuicPacketOpener.Located.Short -> "1-RTT"
                            is QuicPacketOpener.Located.Long ->
                                when (packet.type) {
                                    QuicPacketOpener.LongType.Initial -> "Initial"
                                    QuicPacketOpener.LongType.Handshake -> "Handshake"
                                    QuicPacketOpener.LongType.Retry -> continue
                                    QuicPacketOpener.LongType.ZeroRtt -> "0-RTT"
                                }
                        }
                    val key = "$sender $space"
                    val spaceKeys = keys[space] ?: throw AssertionError("$key packet in datagram $index, but no 0-RTT was offered")
                    try {
                        val opened = QuicPacketOpener.open(datagram, packet, spaceKeys, largest[key] ?: -1)
                        largest[key] = maxOf(largest[key] ?: -1, opened.packetNumber)
                        this.opened[key] = (this.opened[key] ?: 0) + 1
                        if (space == "1-RTT" && opened.payload.containsSubsequence(payload)) sawPayload = true
                    } catch (e: AEADBadTagException) {
                        failures +=
                            "$key packet in datagram $index (${datagram.size} B) failed authentication: ${datagram.toHexString().take(96)}…"
                    }
                }
            }
            return sawPayload
        }

        private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
    }

    private companion object {
        const val KEYLOG_DIR_PROPERTY = "quic.keylog.dir"
    }
}
