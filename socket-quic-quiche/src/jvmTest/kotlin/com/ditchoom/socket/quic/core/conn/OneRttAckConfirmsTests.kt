package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.DEFAULT_PATH_LATENCY
import com.ditchoom.socket.quic.NssKeyLog
import com.ditchoom.socket.quic.QUIC_ISSUED_CONN_ID_LEN
import com.ditchoom.socket.quic.QuicPacketOpener
import com.ditchoom.socket.quic.QuicPacketOpener.Located
import com.ditchoom.socket.quic.QuicStreamId
import com.ditchoom.socket.quic.QuichePairByHand
import com.ditchoom.socket.quic.QuichePairByHand.Datagram
import com.ditchoom.socket.quic.hexToBytes
import com.ditchoom.socket.quic.jvmMigrationSimEnv
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A client whose peer acknowledges its Finished in the Handshake space and its 1-RTT packets, but whose
 * HANDSHAKE_DONE is lost: the peer's packets are written here, sealed with the client's own key log, since a
 * quiche server discards its Handshake keys on the Finished and never acknowledges it. An acknowledged
 * 1-RTT packet confirms the handshake (RFC 9001 §4.1.2), so the client's Application Data PTO runs.
 */
class OneRttAckConfirmsTests {
    private val env = jvmMigrationSimEnv()

    @Test
    fun aClientWhoseHandshakeDoneIsLostStillProbesLostData() =
        runTest {
            val dir = Files.createTempDirectory("one-rtt-ack-confirms").toFile()
            val pair = QuichePairByHand.open(env, configureClient = { env.api.configLogKeys(it) })
            try {
                val keyLog = File(dir, "client.keys")
                assertTrue(env.api.connSetKeylogPath(pair.client, keyLog.path), "keylog path accepted")
                pair.handshakeUpToTheClientsFinished()
                assertEquals(7, pair.streamSend(pair.client, QuicStreamId(0), "request", fin = false))
                val peer =
                    Peer(NssKeyLog.parse(keyLog.readText()).associate { it.label to it.secret.hexToBytes() }, pair.sends(pair.client))
                pair.elapse(DEFAULT_PATH_LATENCY * 2)
                pair.deliver(listOf(peer.handshakeAck(), peer.oneRttAck()), pair.client)

                // The next request is lost.
                assertEquals(6, pair.streamSend(pair.client, QuicStreamId(4), "second", fin = true))
                assertTrue(pair.sends(pair.client).isNotEmpty(), "the second request leaves")
                val probes = ArrayList<Datagram>()
                pair.elapse(PROBE_WITHIN, firing = listOf(pair.client)) { probes += pair.sends(it) }

                assertTrue(
                    probes.any { it.packetTypes == listOf("1-RTT") },
                    "the client must probe its lost request within $PROBE_WITHIN; it sent ${probes.map { it.packetTypes }} and " +
                        "stands ${pair.closed(pair.client)}, its next timer ${pair.nextTimer(pair.client)}",
                )
                assertTrue(pair.confirmed(pair.client), "an acknowledged 1-RTT packet confirms the client's handshake")
            } finally {
                pair.close()
                dir.deleteRecursively()
            }
        }

    /** The server's side of the client's [flight], written from the client's key log [secrets]. */
    private class Peer(
        private val secrets: Map<String, ByteArray>,
        flight: List<Datagram>,
    ) {
        private val packets = flight.flatMap { d -> QuicPacketOpener.locate(d.bytes, QUIC_ISSUED_CONN_ID_LEN).map { d.bytes to it } }
        private val handshake = packets.filter { (_, p) -> p is Located.Long && p.type == QuicPacketOpener.LongType.Handshake }
        private val oneRtt = packets.filter { (_, p) -> p is Located.Short }
        private val clientHandshakeSecret = secrets.getValue("CLIENT_HANDSHAKE_TRAFFIC_SECRET")

        /** The suite whose keys open the client's Handshake packet. */
        private val suite =
            QuicPacketOpener.Suite.entries.first { suite ->
                val (bytes, packet) = handshake.first()
                try {
                    QuicPacketOpener.open(bytes, packet, QuicPacketOpener.keys(suite, clientHandshakeSecret), -1)
                    true
                } catch (_: AEADBadTagException) {
                    false
                }
            }

        private fun keys(label: String) = QuicPacketOpener.keys(suite, secrets.getValue(label))

        private val clientLongHeader = handshake.first().second as Located.Long

        private fun packetNumbers(
            of: List<Pair<ByteArray, Located>>,
            label: String,
        ): List<Long> = of.map { (bytes, packet) -> QuicPacketOpener.open(bytes, packet, keys(label), -1).packetNumber }

        /** A Handshake packet acknowledging every Handshake packet of the flight, the Finished among them. */
        fun handshakeAck(): Datagram =
            Datagram(
                QuicPacketOpener.sealHandshake(
                    dcid = clientLongHeader.scid,
                    scid = clientLongHeader.dcid,
                    packetNumber = PEER_PACKET_NUMBER,
                    payload = ackFrame(packetNumbers(handshake, "CLIENT_HANDSHAKE_TRAFFIC_SECRET")),
                    keys = keys("SERVER_HANDSHAKE_TRAFFIC_SECRET"),
                ),
                0,
            )

        /** A 1-RTT packet acknowledging every 1-RTT packet of the flight; no HANDSHAKE_DONE. */
        fun oneRttAck(): Datagram =
            Datagram(
                QuicPacketOpener.sealOneRtt(
                    dcid = clientLongHeader.scid,
                    packetNumber = PEER_PACKET_NUMBER,
                    payload = ackFrame(packetNumbers(oneRtt, "CLIENT_TRAFFIC_SECRET_0")),
                    keys = keys("SERVER_TRAFFIC_SECRET_0"),
                ),
                0,
            )

        /** An ACK frame (RFC 9000 §19.3) of exactly [acked], no delay. */
        private fun ackFrame(acked: List<Long>): ByteArray {
            assertTrue(acked.isNotEmpty(), "the flight holds packets to acknowledge")
            val runs = ArrayList<LongRange>()
            for (pn in acked.sortedDescending()) {
                val last = runs.lastOrNull()
                if (last != null && last.first == pn + 1) runs[runs.size - 1] = pn..last.last else runs += pn..pn
            }
            var frame =
                byteArrayOf(0x02) + varint(runs.first().last) + varint(0) + varint(runs.size - 1L) + varint(runs.first().count() - 1L)
            for ((previous, run) in runs.zipWithNext()) {
                frame += varint(previous.first - run.last - 2) + varint(run.count() - 1L)
            }
            return frame
        }

        private fun varint(v: Long): ByteArray =
            when {
                v < 0x40 -> byteArrayOf(v.toByte())
                v < 0x4000 -> byteArrayOf((0x40 or (v shr 8).toInt()).toByte(), v.toByte())
                else -> ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }.also { it[0] = (it[0].toInt() or 0x80).toByte() }
            }
    }

    private companion object {
        /** Several of the client's PTOs on the pair's 120 ms round trip, and far short of its idle timeout. */
        val PROBE_WITHIN = 5.seconds

        /** Past every packet number the real server used, so the client reads these as new. */
        const val PEER_PACKET_NUMBER = 100L
    }
}
