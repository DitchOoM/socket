package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC §9 conformance witnesses run against real sockets in Phase 2:
 *
 * - **DNS** — the one-shot resolve ergonomics + proof that touching [UdpSocket] installs a process-wide
 *   platform resolver into buffer-flow (so [SocketAddress.resolve] is live for ICE / quiche).
 * - **standalone STUN** — a one-shot Binding Request/Response over loopback (a local fake STUN server),
 *   exercising unconnected send-with-destination, per-packet `peer` recovery, and reply routing — the
 *   "keeps the 80% ergonomics pleasant" axis, with no external network dependency.
 */
@OptIn(ExperimentalDatagramApi::class)
class UdpWitnessTests {
    // ---- DNS witness ----

    @Test
    fun dnsResolvesHostnameAndInstallsProcessWideResolver() =
        runBlocking(Dispatchers.IO) {
            // Hostname resolution through the installed platform resolver (localhost is always resolvable
            // offline). The result owns its InetSocketAddress — zero-alloc reuse as a send target.
            val loopback = UdpSocket.resolve("localhost", 4711)
            assertEquals(4711, loopback.port)
            assertIs<InternedJvmSocketAddress>(loopback)
            assertTrue(
                InetAddress.getByName(loopback.host).isLoopbackAddress,
                "localhost should resolve to a loopback literal, got ${loopback.host}",
            )

            // Touching UdpSocket installed the resolver into buffer-flow, so SocketAddress.resolve — the
            // canonical entry point (RFC §10.1) — now handles hostnames process-wide, not just literals.
            val numeric = SocketAddress.resolve("127.0.0.1", 53)
            assertEquals("127.0.0.1", numeric.host)
            assertEquals(53, numeric.port)

            val viaGlobal = SocketAddress.resolve("localhost", 80)
            assertTrue(InetAddress.getByName(viaGlobal.host).isLoopbackAddress)
        }

    // ---- standalone STUN witness (local, deterministic) ----

    @Test
    fun stunOneShotBindingRequestResponse() =
        runBlocking(Dispatchers.IO) {
            val server = UdpSocket.bind("127.0.0.1", 0)
            val client = UdpSocket.bind("127.0.0.1", 0)
            try {
                val serverAddr = server.localAddress!!
                val txId = ByteArray(12) { (it + 1).toByte() }

                // Fake STUN server: one-shot — receive a Binding Request, reflect the peer's address back
                // as an XOR-MAPPED-ADDRESS in a Binding Success Response.
                val serverJob =
                    launch {
                        val req = assertIs<DatagramReadResult.Received>(withTimeout(5_000) { server.receive() }).datagram
                        val reqBytes = req.payload.readByteArray(req.payload.remaining())
                        assertTrue(isBindingRequest(reqBytes), "expected a STUN Binding Request")
                        val peer = req.peer // per-packet source == the client's mapped address
                        server.send(BufferFactory.Default.wrap(bindingSuccess(txId, peer.host, peer.port)), to = peer)
                    }

                client.send(BufferFactory.Default.wrap(bindingRequest(txId)), to = serverAddr)
                val resp = assertIs<DatagramReadResult.Received>(withTimeout(5_000) { client.receive() }).datagram
                val respBytes = resp.payload.readByteArray(resp.payload.remaining())
                val (mappedHost, mappedPort) = parseXorMappedAddress(respBytes, txId)

                // The server observed the client's real source — its mapped address is the client's own.
                assertEquals("127.0.0.1", mappedHost)
                assertEquals(client.localAddress!!.port, mappedPort)
                assertEquals(serverAddr, resp.peer)
                serverJob.join()
            } finally {
                server.close()
                client.close()
            }
        }

    // ---- minimal STUN (RFC 5389) codec, IPv4 only — enough for the witness ----

    private val magic = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

    private fun bindingRequest(txId: ByteArray): ByteArray {
        val b = ByteArray(20)
        b[0] = 0x00
        b[1] = 0x01 // Binding Request
        b[2] = 0x00
        b[3] = 0x00 // no attributes
        magic.copyInto(b, 4)
        txId.copyInto(b, 8)
        return b
    }

    private fun isBindingRequest(b: ByteArray): Boolean =
        b.size >= 20 &&
            b[0] == 0x00.toByte() &&
            b[1] == 0x01.toByte() &&
            b[4] == magic[0] &&
            b[5] == magic[1] &&
            b[6] == magic[2] &&
            b[7] == magic[3]

    private fun bindingSuccess(
        txId: ByteArray,
        host: String,
        port: Int,
    ): ByteArray {
        val ip = InetAddress.getByName(host).address
        require(ip.size == 4) { "STUN witness handles IPv4 only" }
        val msg = ByteArray(32)
        msg[0] = 0x01
        msg[1] = 0x01 // Binding Success Response (0x0101)
        msg[2] = 0x00
        msg[3] = 0x0C // message length = 12 (one 12-byte attribute)
        magic.copyInto(msg, 4)
        txId.copyInto(msg, 8)
        msg[20] = 0x00
        msg[21] = 0x20 // XOR-MAPPED-ADDRESS
        msg[22] = 0x00
        msg[23] = 0x08 // value length 8
        msg[24] = 0x00 // reserved
        msg[25] = 0x01 // family IPv4
        val xport = port xor 0x2112
        msg[26] = ((xport ushr 8) and 0xFF).toByte()
        msg[27] = (xport and 0xFF).toByte()
        for (i in 0 until 4) msg[28 + i] = (ip[i].toInt() xor magic[i].toInt()).toByte()
        return msg
    }

    private fun parseXorMappedAddress(
        b: ByteArray,
        txId: ByteArray,
    ): Pair<String, Int> {
        require(b.size >= 32) { "STUN response too short" }
        require(b[0] == 0x01.toByte() && b[1] == 0x01.toByte()) { "not a Binding Success Response" }
        for (i in 0 until 12) require(b[8 + i] == txId[i]) { "transaction id mismatch" }
        require(b[20] == 0x00.toByte() && b[21] == 0x20.toByte()) { "expected XOR-MAPPED-ADDRESS attribute" }
        require(b[25] == 0x01.toByte()) { "expected IPv4 family" }
        val xport = ((b[26].toInt() and 0xFF) shl 8) or (b[27].toInt() and 0xFF)
        val port = xport xor 0x2112
        val ip = ByteArray(4) { (b[28 + it].toInt() xor magic[it].toInt()).toByte() }
        return InetAddress.getByAddress(ip).hostAddress!! to port
    }
}
