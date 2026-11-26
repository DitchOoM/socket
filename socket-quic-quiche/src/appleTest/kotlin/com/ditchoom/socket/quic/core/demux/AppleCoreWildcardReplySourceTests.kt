package com.ditchoom.socket.quic.core.demux

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.appleHostInterfaceAddresses
import com.ditchoom.socket.quic.core.edge.connectCore
import com.ditchoom.socket.quic.core.edge.echoOver
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.testkit.skip.SkipGate
import com.ditchoom.socket.testkit.skip.SkipReason
import com.ditchoom.socket.testkit.skip.recordSkip
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * On Darwin a server bound to the wildcard answers each client from the address the client sent to (the RFC 3542
 * packet-info reply source), so a client that dials the host's interface address, not loopback, completes and echoes:
 * had the reply left from the address the kernel picks, the client's connected socket would drop it.
 */
class AppleCoreWildcardReplySourceTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aClientDialingTheHostsInterfaceAddressOfAWildcardServerCompletesAndEchoes() =
        runBlocking {
            val dialed = appleHostInterfaceAddresses().firstOrNull { '.' in it }
            if (dialed == null) {
                recordSkip(
                    AppleCoreWildcardReplySourceTests::class,
                    SkipReason.HostBehaviourDiffers("the host has no IPv4 route, so it has no interface address to dial"),
                    SkipGate.HostCannotProvideIt("an IPv4 interface address"),
                )
                return@runBlocking
            }
            withCoreEchoServer(bind = wildcardV4()) { server ->
                val client = connectCore(server.port, host = dialed, loops = loops)
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
