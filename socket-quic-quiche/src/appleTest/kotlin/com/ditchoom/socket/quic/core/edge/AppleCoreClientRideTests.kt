package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.udp.Granted
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/** On Apple a core client's datagrams ride Network.framework, not a socket of the loop's stack. */
class AppleCoreClientRideTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun aClientRidesNetworkFrameworkAndEchoes() =
        runBlocking {
            assertIs<ClientRide.NetworkFramework>(clientRideOfThisPlatform)
            withCoreEchoServer { server ->
                val client = connectCore(server.port, loops = loops)
                try {
                    // Only a Network.framework connection leaves its buffer sizes unreported.
                    assertIs<Granted.NotReported>(client.socket.granted, "the client's datagrams rode Network.framework")
                    val payload = Random(3).nextBytes(64 * 1024)
                    assertContentEquals(payload, echoOver(client.connection.openStream(), payload))
                } finally {
                    client.connection.close(QuicError.NoError)
                }
            }
        }
}
