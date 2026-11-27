@file:OptIn(ExperimentalDatagramApi::class, ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.flow.SocketAddress
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.udp.LentDrop
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlinx.coroutines.async
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a lent channel's send refuses, as socket-udp's channels refuse an ICMP-unreachable or too-large datagram, drops
 * that one datagram and nothing more; and a send that never returns holds the channel only for the drain bound after
 * its connection closed.
 */
class CoreLentSendFaultTests {
    @Test
    fun aLentServerWhoseSendsToOnePeerAreRefusedKeepsEchoingWithTheOthers() =
        coreTest("CoreLentSendFaultTests.aLentServerWhoseSendsToOnePeerAreRefusedKeepsEchoingWithTheOthers") {
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            // The first peer the server answers is served; every other is unreachable, as after an ICMP port unreachable.
            val served = AtomicReference<SocketAddress>(shared.localAddress)
            val faulty =
                FaultyChannel(shared.quic) { n, to ->
                    if (n == 1) served.store(to)
                    if (to == served.load()) SendFault.Passes else SendFault.Refuses(DatagramSendError.PortUnreachable)
                }
            val (listener, handlers) = echoingListener(faulty)
            try {
                val first = connectCore(shared.localAddress.port)
                assertSharedPortEchoes(first.connection.public(), 0)
                val unreachable = async { runCatching { connectCore(shared.localAddress.port, timeout = 2.seconds) } }
                assertSharedPortEchoes(first.connection.public(), 1)
                assertTrue(unreachable.await().isFailure, "the unreachable peer never got an answer")
                assertSharedPortEchoes(first.connection.public(), 2)
                first.connection.closeNow(QuicError.NoError)
            } finally {
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }

    @Test
    fun aLentClientWhoseSendsAreSometimesRefusedAsTooLargeKeepsItsConnection() =
        coreTest("CoreLentSendFaultTests.aLentClientWhoseSendsAreSometimesRefusedAsTooLargeKeepsItsConnection") {
            val (listener, handlers) = echoingOwnedListener()
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val faulty =
                FaultyChannel(shared.quic) { n, _ ->
                    if (n > HANDSHAKE_SENDS &&
                        n % EVERY == 0
                    ) {
                        SendFault.Refuses(DatagramSendError.TooLarge(1_500, 1_200))
                    } else {
                        SendFault.Passes
                    }
                }
            val loops = LoopSet(1)
            try {
                val client = lentClient(faulty, listener.port, loops)
                repeat(ROUNDS) { assertSharedPortEchoes(client.connection.public(), it) }
                assertTrue(client.lentDrops(LentDrop.SendTooLarge) > 0, "the refused sends were counted as too large")
                client.connection.closeNow(QuicError.NoError)
            } finally {
                loops.close()
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }

    @Test
    fun aSendThatNeverReturnsHoldsTheChannelOnlyForTheDrainBoundAfterTheClose() =
        coreTest("CoreLentSendFaultTests.aSendThatNeverReturnsHoldsTheChannelOnlyForTheDrainBoundAfterTheClose") {
            val (listener, handlers) = echoingOwnedListener()
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val hangFrom = AtomicInt(Int.MAX_VALUE)
            val faulty = FaultyChannel(shared.quic) { n, _ -> if (n >= hangFrom.load()) SendFault.Hangs else SendFault.Passes }
            val loops = LoopSet(1)
            try {
                val client = lentClient(faulty, listener.port, loops)
                assertSharedPortEchoes(client.connection.public(), 0)
                hangFrom.store(0)
                client.connection.closeNow(QuicError.NoError)
                assertTrue(eventually { client.loop.connections.value() == 0L }, "the connection left its loop")
                // The sender is stuck in a send; the channel is claimable again once the drain bound cut it.
                assertTrue(
                    eventually(within = 5.seconds) { claimable(faulty) },
                    "the claim was given back",
                )
            } finally {
                loops.close()
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }

    private companion object {
        const val HANDSHAKE_SENDS = 6
        const val EVERY = 5
        const val ROUNDS = 8
    }
}
