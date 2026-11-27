package com.ditchoom.socket.quic.core.demux

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.RouteSource
import com.ditchoom.socket.quic.core.edge.coreConnect
import com.ditchoom.socket.quic.core.edge.coreTestOptions
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.edge.openStream
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.assumeWildcardRepliesLeaveFromArrival
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.platformQuicheApi
import com.ditchoom.socket.quic.network
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A server bound to the wildcard answers from the address each client sent to. The client here sends from 127.0.0.1
 * to another of the host's addresses, so the address the kernel would pick for a reply to 127.0.0.1 is not the one
 * the client's connected socket accepts; a peer on another machine meets the same choice whenever the server's host has
 * more than one address, as any IPv6 host with temporary addresses does. A stack whose wildcard replies leave from an
 * address the kernel picks skips it, loudly.
 */
class CoreWildcardReplySourceTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aClientThatSendsFromAnotherOfTheHostsAddressesCompletesAndEchoes() {
        assumeWildcardRepliesLeaveFromArrival(CoreWildcardReplySourceTests::class)
        coreTest("CoreWildcardReplySourceTests.aClientThatSendsFromAnotherOfTheHostsAddressesCompletesAndEchoes") {
            val dialed = anotherLocalAddress()
            withCoreEchoServer(bind = wildcardV4()) { server ->
                val client =
                    coreConnect(
                        host = dialed,
                        port = server.port,
                        serverName = "localhost",
                        options = coreTestOptions,
                        factory = BufferFactory.network(),
                        api = platformQuicheApi(),
                        timeout = 10.seconds,
                        readPolicy = ReadPolicy.Bounded(10.seconds),
                        writePolicy = WritePolicy.Bounded(10.seconds),
                        loops = loops,
                        routeSource = { RouteSource.Resolved(LOOPBACK) },
                    )
                try {
                    val payload = Random(1).nextBytes(1_024)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                } finally {
                    client.connection.close(QuicError.NoError)
                }
                assertEquals(1L, server.counts.accepted)
            }
        }
    }
}

private const val LOOPBACK = "127.0.0.1"

/**
 * An IPv4 address of this host other than 127.0.0.1: 127.0.0.2 where it can be bound (every Linux host, a Mac with the
 * alias), else the first address of an interface that is up.
 */
private fun anotherLocalAddress(): String {
    val alias = "127.0.0.2"
    val aliased = runCatching { DatagramSocket(InetSocketAddress(alias, 0)).close() }.isSuccess
    if (aliased) return alias
    return NetworkInterface
        .getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress }
        .first()
}
