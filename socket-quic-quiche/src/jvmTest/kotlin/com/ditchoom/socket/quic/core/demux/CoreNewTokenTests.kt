package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.socket.quic.AddressTokenMemory
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.KeptToken
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.QuicSessionKey
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.QuicheSendInfo
import com.ditchoom.socket.quic.core.conn.ClientTokens
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
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
import kotlin.time.Duration.Companion.seconds

/**
 * NEW_TOKEN (RFC 9000 §8.1.3): a server hands each client a token, and the client's next connection proves its address.
 * Off on both ends unless asked; a client sends each token at most once, and gets back one no datagram carried.
 */
class CoreNewTokenTests {
    @get:org.junit.Rule
    val noLeak: org.junit.rules.TestRule = NoServerLoopLeft()

    private val loops = LoopSet(2)

    /** This test's own token memory: the process's would carry tokens from one test into the next. */
    private val memory = AddressTokenMemory()

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
    fun aClientKeepsAndSendsNoTokenUnlessAsked() =
        runBlocking {
            val config = ServerConfig(addressValidation = AddressValidation.Retry(), futureTokens = FutureTokens.Issued())
            withCoreEchoServer(config) { server ->
                connectAndEcho(server.port, ClientTokens.NotKept)
                connectAndEcho(server.port, ClientTokens.NotKept)
                assertEquals(2L, server.counts.newTokensQueued, "the server issued a token to each")
                assertEquals(2L, server.counts.retriesSent, "neither connection presented one")
                assertEquals(0L, server.counts.tokensProven)
                assertSame(KeptToken.None, AddressTokenMemory.Process.take(keyOf(server.port)), "nor kept one")
            }
        }

    @Test
    fun aServerIssuesNoTokenUnlessAsked() =
        runBlocking {
            withCoreEchoServer(ServerConfig(addressValidation = AddressValidation.Retry())) { server ->
                connectAndEcho(server.port)
                connectAndEcho(server.port)
                assertEquals(0L, server.counts.newTokensQueued)
                assertEquals(2L, server.counts.retriesSent)
                assertEquals(0L, server.counts.tokensProven)
                assertSame(KeptToken.None, memory.take(keyOf(server.port)))
            }
        }

    @Test
    fun aTokenAConnectNeverSentGoesBackAndTheNextConnectProvesItsAddress() =
        runBlocking {
            withCoreEchoServer(ServerConfig(futureTokens = FutureTokens.Issued())) { server ->
                connectAndEcho(server.port)
                val failed =
                    runCatching {
                        connectCore(
                            server.port,
                            loops = loops,
                            api = NothingToSend(platformQuicheApi()),
                            options = coreTestOptions.copy(handshakePolicy = HandshakePolicy.Bounded(1.seconds)),
                            tokens = ClientTokens.Kept(memory),
                        )
                    }
                assertTrue(failed.isFailure, "quiche let the connect send nothing, so its handshake timed out")
                connectAndEcho(server.port)
                assertEquals(1L, server.counts.tokensProven, "the token the failed connect never sent proved the third")
            }
        }

    @Test
    fun aTokenThatWasSentIsNeverSentAgain() =
        runBlocking {
            var port = 0
            withCoreEchoServer(ServerConfig(futureTokens = FutureTokens.Issued())) { server ->
                port = server.port
                connectAndEcho(port)
            }
            // A server that issues none: the token the client sends it is not replaced.
            withCoreEchoServer(ServerConfig(addressValidation = AddressValidation.Retry()), bind = loopbackV4(port)) { server ->
                connectAndEcho(port)
                assertSame(KeptToken.None, memory.take(keyOf(port)), "the sent token stays used")
                connectAndEcho(port)
                assertEquals(2L, server.counts.retriesSent)
                assertEquals(0L, server.counts.droppedToken)
            }
        }

    @Test
    fun aTokenHandedBackIsOfferedAgainUnlessANewerOneWasKept() {
        val key = QuicSessionKey.of("example.test", 443, coreTestOptions)
        memory.keep(key, bytes(1, 2, 3))
        val first = assertIs<KeptToken.Held>(memory.take(key))
        assertSame(KeptToken.None, memory.take(key), "lent, not offered")
        memory.restore(key, first.token)
        assertSame(first.token, assertIs<KeptToken.Held>(memory.take(key)).token, "handed back, offered again")

        memory.keep(key, bytes(4, 5))
        memory.restore(key, first.token)
        val newer = assertIs<KeptToken.Held>(memory.take(key))
        assertContentEquals(byteArrayOf(4, 5), ByteArray(2) { newer.token[it] }, "a newer token wins over a returned one")
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

    private fun keyOf(port: Int) = QuicSessionKey.of("localhost", port, coreTestOptions)

    private fun bytes(vararg values: Byte) =
        BufferFactory.Default.allocate(values.size).apply {
            writeBytes(values)
            resetForRead()
        }

    /** quiche gives this client nothing to send. */
    private class NothingToSend(
        private val inner: QuicheApi,
    ) : QuicheApi by inner {
        override fun connSend(
            conn: QuicheConn,
            buf: Long,
            bufLen: Int,
            sendInfo: QuicheSendInfo,
        ): Int = -1
    }

    private suspend fun connectAndEcho(
        port: Int,
        tokens: ClientTokens = ClientTokens.Kept(memory),
    ) {
        val client = connectCore(port, loops = loops, tokens = tokens)
        try {
            val payload = Random(port).nextBytes(2_048)
            assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
        } finally {
            client.connection.close(QuicError.NoError)
        }
    }
}
