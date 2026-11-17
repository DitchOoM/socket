package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.AddressTokenMemory
import com.ditchoom.socket.quic.KeptToken
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

/** NEW_TOKEN (RFC 9000 §8.1.3): a server hands each client a token, and the client's next connection proves its address. */
class CoreNewTokenTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aReturningClientPresentsItsTokenAndSkipsTheRetryRoundTrip() =
        runBlocking {
            val config = ServerConfig(addressValidation = AddressValidation.Retry(), futureTokens = FutureTokens.Issued())
            withCoreEchoServer(config) { server ->
                connectAndEcho(server.port)
                assertEquals(1L, server.counts.retriesSent, "the first connection is validated by a Retry")
                assertEquals(1L, server.counts.newTokensQueued, "and is handed a token for the next")

                connectAndEcho(server.port)
                assertEquals(1L, server.counts.retriesSent, "the second connection's token stands in for a Retry")
                assertEquals(1L, server.counts.tokensProven)
                assertEquals(2L, server.counts.accepted)
                assertEquals(0L, server.counts.droppedToken)
            }
        }

    @Test
    fun aTokenProvesTheAddressOnAServerThatDoesNotRetry() =
        runBlocking {
            withCoreEchoServer(ServerConfig(futureTokens = FutureTokens.Issued())) { server ->
                connectAndEcho(server.port)
                connectAndEcho(server.port)
                assertEquals(0L, server.counts.retriesSent)
                assertEquals(1L, server.counts.tokensProven, "the anti-amplification limit is lifted for the second")
            }
        }

    @Test
    fun anExpiredTokenFallsBackToRetryAndTheConnectionCompletes() =
        runBlocking {
            val config =
                ServerConfig(addressValidation = AddressValidation.Retry(), futureTokens = FutureTokens.Issued(lifetime = 1.nanoseconds))
            withCoreEchoServer(config) { server ->
                connectAndEcho(server.port)
                connectAndEcho(server.port)
                assertEquals(2L, server.counts.retriesSent, "the expired token is treated as none: Retry, not a drop")
                assertEquals(0L, server.counts.tokensProven)
                assertEquals(0L, server.counts.droppedToken)
                assertEquals(2L, server.counts.accepted)
            }
        }

    @Test
    fun aTokenFromARestartedServerFallsBackToRetryAndTheConnectionCompletes() =
        runBlocking {
            var port = 0
            withCoreEchoServer(ServerConfig(futureTokens = FutureTokens.Issued())) { server ->
                port = server.port
                connectAndEcho(port)
            }
            // The same address, a new key: the token the client holds is not one this server can check (RFC 9000 §8.1.3 note).
            withCoreEchoServer(ServerConfig(addressValidation = AddressValidation.Retry()), bind = loopbackV4(port)) { server ->
                connectAndEcho(port)
                assertEquals(1L, server.counts.retriesSent)
                assertEquals(0L, server.counts.droppedToken)
                assertEquals(1L, server.counts.accepted)
            }
        }

    @Test
    fun aNewTokenIsBoundToTheClientsIpAddressNotItsPort() {
        val tokens = AddressTokens(BufferFactory.Default, Random(1), lifetimeNanos = 1_000L, futureLifetimeNanos = 1_000L)
        val peer = PathKey(family = 4, port = 5_000, hi = 0L, lo = 0x7f000001L)
        val anyCid = CidRead().apply { set(10L, 20L, 0L, 16) }
        val token = BufferFactory.Default.allocate(256)
        val length = tokens.mintFuture(token, nowNanos = 100L, peer)

        assertSame(TokenCheck.Proven, tokens.check(token, length, 200L, anyCid, peer))
        assertSame(TokenCheck.Proven, tokens.check(token, length, 200L, anyCid, peer.copy(port = 6_000)), "a new connection's new port")
        assertSame(TokenCheck.Unproven, tokens.check(token, length, 200L, anyCid, peer.copy(lo = 0x7f000002L)), "another address")
        assertSame(TokenCheck.Unproven, tokens.check(token, length, 1_101L, anyCid, peer), "expired")
        assertSame(TokenCheck.Unproven, tokens.check(token, length - 1, 200L, anyCid, peer), "truncated")
        val elsewhere = AddressTokens(BufferFactory.Default, Random(2), lifetimeNanos = 1_000L)
        assertSame(TokenCheck.Unproven, elsewhere.check(token, length, 200L, anyCid, peer), "another server's key")
        for (i in 0 until length) {
            val original = token[i]
            token.set(i, (original.toInt() xor 1).toByte())
            assertTrue(tokens.check(token, length, 200L, anyCid, peer) !is TokenCheck.Proven, "a flipped bit at $i is not accepted")
            token.set(i, original)
        }
    }

    @Test
    fun retryAndNewTokenTokensAreNeverTakenForEachOther() {
        val tokens = AddressTokens(BufferFactory.Default, Random(1), lifetimeNanos = 1_000L)
        val peer = PathKey(family = 4, port = 5_000, hi = 0L, lo = 0x7f000001L)
        val newCid = CidRead().apply { set(10L, 20L, 0L, 16) }
        val retry = BufferFactory.Default.allocate(256)
        val retryLength = tokens.mint(retry, 100L, CidRead().apply { set(1L, 2L, 3L, 20) }, newCid.c0, newCid.c1, peer)
        val future = BufferFactory.Default.allocate(256)
        val futureLength = tokens.mintFuture(future, 100L, peer)

        assertIs<TokenCheck.Valid>(tokens.check(retry, retryLength, 200L, newCid, peer))
        assertNotEquals(retry[0], future[0], "the first byte says which kind a token is (RFC 9000 §8.1.1)")
        retry.set(0, future[0])
        assertSame(TokenCheck.Unproven, tokens.check(retry, retryLength, 200L, newCid, peer), "a Retry token relabelled")
        assertFalse(tokens.check(future, futureLength, 200L, newCid, peer) is TokenCheck.Valid)
    }

    @Test
    fun everyNewTokenIsUniqueAndCarriesNoAddressOrTimeInTheClear() {
        val tokens = AddressTokens(BufferFactory.Default, Random(1), lifetimeNanos = 1_000L)
        val peer = PathKey(family = 4, port = 5_000, hi = 0L, lo = 0x7f000001L)
        val a = BufferFactory.Default.allocate(256)
        val b = BufferFactory.Default.allocate(256)
        val length = tokens.mintFuture(a, 100L, peer)
        tokens.mintFuture(b, 100L, peer)
        val first = ByteArray(length) { a[it] }
        val second = ByteArray(length) { b[it] }
        assertFalse(first.contentEquals(second), "two tokens for one client at one instant differ (RFC 9000 §8.1.3)")
        // Same instant, same lifetime: an expiry in the clear would be the same bytes in both.
        assertFalse(first.copyOfRange(17, 25).contentEquals(second.copyOfRange(17, 25)), "the expiry is sealed")
    }

    @Test
    fun aKeptTokenIsTakenOnce() {
        val memory = AddressTokenMemory()
        val key = QuicSessionKey.of("example.test", 443, coreTestOptions)
        val token = BufferFactory.Default.allocate(3)
        token.writeBytes(byteArrayOf(1, 2, 3))
        token.resetForRead()
        memory.keep(key, token)
        val held = assertIs<KeptToken.Held>(memory.take(key))
        assertContentEquals(byteArrayOf(1, 2, 3), ByteArray(3) { held.token[it] })
        assertSame(KeptToken.None, memory.take(key), "a token is offered to one connection attempt only")
    }

    private suspend fun connectAndEcho(port: Int) {
        val client = connectCore(port, loops = loops)
        try {
            val payload = Random(port).nextBytes(2_048)
            assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }
}
