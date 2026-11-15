package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Version Negotiation and Retry: the packets a server answers with before it has a connection. */
class CoreServerStatelessTests {
    @Test
    fun anInitialOfAnUnsupportedVersionGetsAVersionNegotiationListingQuichesVersionsAndNoConnection() =
        runBlocking {
            withCoreEchoServer { server ->
                val dcid = Random(1).nextBytes(16)
                val scid = Random(2).nextBytes(8)
                val reply = exchange(server.port, initial(version = 0x1a2a3a4a, dcid = dcid, scid = scid))

                assertTrue(reply.remaining() >= 7, "a Version Negotiation packet came back")
                assertTrue(reply.get(0).toInt() and 0x80 != 0, "it has a long header")
                assertEquals(0, reply.getInt(1), "its version is 0")
                val dcidLength = reply.get(5).toInt() and 0xFF
                assertEquals(scid.size, dcidLength, "it addresses our source id")
                assertContentEquals(scid, ByteArray(dcidLength) { reply.get(6 + it) })
                val scidLength = reply.get(6 + dcidLength).toInt() and 0xFF
                assertContentEquals(dcid, ByteArray(scidLength) { reply.get(7 + dcidLength + it) }, "it echoes our destination id")
                val versions = (7 + dcidLength + scidLength until reply.limit() step 4).map { reply.getInt(it) }
                assertEquals(listOf(1), versions, "quiche lists version 1")
                assertEquals(1L, server.counts.versionNegotiationsSent)
                assertEquals(0L, server.counts.accepted)
            }
        }

    @Test
    fun aRuntInitialOfAnUnsupportedVersionIsDroppedUnanswered() =
        runBlocking {
            withCoreEchoServer { server ->
                val runt = initial(version = 0x1a2a3a4a, dcid = Random(1).nextBytes(8), scid = Random(2).nextBytes(8), size = 600)
                DatagramChannel.open().use { socket ->
                    socket.send(ByteBuffer.wrap(runt), InetSocketAddress("127.0.0.1", server.port))
                }
                delay(300.milliseconds)
                assertEquals(0L, server.counts.versionNegotiationsSent)
                assertEquals(1L, server.counts.droppedRunt)
            }
        }

    @Test
    fun aClientCompletesTheHandshakeThroughRetry() =
        runBlocking {
            withCoreEchoServer(ServerConfig(addressValidation = AddressValidation.Retry())) { server ->
                val client = connectCore(server.port)
                try {
                    val payload = Random(7).nextBytes(4_096)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                } finally {
                    client.connection.close(QuicError.NoError)
                }
                assertTrue(server.counts.retriesSent >= 1L, "the server sent a Retry")
                assertEquals(1L, server.counts.accepted, "and accepted the connection once the token came back")
            }
        }

    @Test
    fun anInitialWithAForgedTokenIsDroppedAndCreatesNoConnection() =
        runBlocking {
            withCoreEchoServer(ServerConfig(addressValidation = AddressValidation.Retry())) { server ->
                val forged = initial(version = 1, dcid = Random(1).nextBytes(16), scid = Random(2).nextBytes(8), token = Random(3).nextBytes(45))
                DatagramChannel.open().use { socket ->
                    socket.send(ByteBuffer.wrap(forged), InetSocketAddress("127.0.0.1", server.port))
                }
                delay(300.milliseconds)
                assertEquals(1L, server.counts.droppedToken)
                assertEquals(0L, server.counts.accepted)
                assertEquals(0L, server.counts.retriesSent)
            }
        }

    @Test
    fun aTokenIsCheckedAgainstItsPeerItsConnectionIdItsKeyAndItsLifetime() {
        val factory = BufferFactory.Default
        val tokens = AddressTokens(factory, Random(1), lifetimeNanos = 1_000L)
        val peer = PathKey(family = 4, port = 5_000, hi = 0L, lo = 0x7f000001L)
        val original = CidRead().apply { set(1L, 2L, 3L, 20) }
        val newCid = CidRead().apply { set(10L, 20L, 0L, 16) }
        val token = factory.allocate(256)
        val length = tokens.mint(token, nowNanos = 100L, original, newCid.c0, newCid.c1, peer)

        val valid = assertIs<TokenCheck.Valid>(tokens.check(token, length, nowNanos = 200L, newCid, peer))
        assertEquals(20, valid.originalDcidLength)
        assertEquals(1L, token.getLong(valid.originalDcidOffset))

        assertSame(TokenCheck.Expired, tokens.check(token, length, nowNanos = 1_101L, newCid, peer))
        assertSame(TokenCheck.Forged, tokens.check(token, length, 200L, newCid, peer.copy(port = 5_001)), "another source port")
        assertSame(TokenCheck.Forged, tokens.check(token, length, 200L, newCid, peer.copy(lo = 0x7f000002L)), "another source address")
        assertSame(
            TokenCheck.Forged,
            tokens.check(token, length, 200L, CidRead().apply { set(10L, 21L, 0L, 16) }, peer),
            "another connection id",
        )
        assertSame(TokenCheck.Malformed, tokens.check(token, length - 1, 200L, newCid, peer), "a truncated token")
        assertSame(TokenCheck.Malformed, tokens.check(token, 10, 200L, newCid, peer), "a runt token")

        val elsewhere = AddressTokens(factory, Random(2), lifetimeNanos = 1_000L)
        assertSame(TokenCheck.Forged, elsewhere.check(token, length, 200L, newCid, peer), "another server's key")

        for (i in 0 until length) {
            val original = token[i]
            token.set(i, (original.toInt() xor 1).toByte())
            val verdict = tokens.check(token, length, 200L, newCid, peer)
            assertTrue(verdict !is TokenCheck.Valid, "a flipped bit at $i is not accepted: $verdict")
            token.set(i, original)
        }
    }

    @Test
    fun aTokenSurvivesBeingReadBackFromAnotherBuffer() {
        val factory = BufferFactory.Default
        val tokens = AddressTokens(factory, Random(1), lifetimeNanos = 1_000_000L)
        val peer = PathKey(family = 6, port = 1, hi = 0x20010db800000000L, lo = 1L)
        val newCid = CidRead().apply { set(7L, 8L, 0L, 16) }
        val minted = factory.allocate(256)
        val length = tokens.mint(minted, 0L, CidRead().apply { set(5L, 0L, 0L, 8) }, newCid.c0, newCid.c1, peer)
        val received = factory.allocate(256)
        for (i in 0 until length) received.set(i, minted[i])
        val checked = assertIs<TokenCheck.Valid>(tokens.check(received, length, 10L, newCid, peer))
        assertEquals(8, checked.originalDcidLength)
    }

    /** A client Initial of [size] bytes that names [version]: a header quiche parses, padded with zeros. */
    private fun initial(
        version: Int,
        dcid: ByteArray,
        scid: ByteArray,
        token: ByteArray = ByteArray(0),
        size: Int = 1_200,
    ): ByteArray {
        val out = ByteBuffer.allocate(size)
        out.put(0xC0.toByte())
        out.putInt(version)
        out.put(dcid.size.toByte()).put(dcid)
        out.put(scid.size.toByte()).put(scid)
        out.put(token.size.toByte()).put(token)
        val payload = size - out.position() - 2
        out.put((0x40 or (payload shr 8)).toByte()).put(payload.toByte())
        return out.array()
    }

    /** Sends [datagram] to the server and returns what it answers with. */
    private fun exchange(
        port: Int,
        datagram: ByteArray,
    ): ByteBuffer =
        DatagramChannel.open().use { socket ->
            socket.socket().soTimeout = 5_000
            socket.send(ByteBuffer.wrap(datagram), InetSocketAddress("127.0.0.1", port))
            val packet = java.net.DatagramPacket(ByteArray(2_000), 2_000)
            socket.socket().receive(packet)
            ByteBuffer.wrap(packet.data, 0, packet.length)
        }
}
