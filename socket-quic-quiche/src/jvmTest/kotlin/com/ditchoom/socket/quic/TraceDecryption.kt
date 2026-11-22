package com.ditchoom.socket.quic

import javax.crypto.AEADBadTagException

/** Opens a client-vantage trace's datagrams with a key log's secrets, packet by packet. */
internal class TraceDecryption(
    private val sent: List<ByteArray>,
    private val received: List<ByteArray>,
    private val secrets: Map<String, ByteArray>,
    echoPayload: String,
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
    private val payload = echoPayload.encodeToByteArray()

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
