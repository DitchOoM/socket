@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.managed
import com.ditchoom.socket.IpFamily
import com.ditchoom.socket.ResolvedAddress
import com.ditchoom.socket.quic.QuicEndpoint
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import com.ditchoom.socket.udp.UdpSocket
import com.ditchoom.socket.udp.demultiplex
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A lent connect whose factory cannot give its io addressable slots fails in its setup, off the loop: the loop and
 * every other connection on it go on, and the channel is free again.
 */
class CoreLentSetupFailureTests {
    /** The network's memory, except a datagram slot's, which has no address. */
    private class NoSlotMemory(
        private val real: BufferFactory = BufferFactory.network(),
    ) : BufferFactory by real {
        override fun allocate(
            size: Int,
            byteOrder: ByteOrder,
        ): PlatformBuffer = if (size == MAX_DATAGRAM) BufferFactory.managed().allocate(size, byteOrder) else real.allocate(size, byteOrder)
    }

    @Test
    fun aLentConnectWhoseSlotsCannotBeMadeFailsAloneAndLeavesItsChannelFree() =
        coreTest("CoreLentSetupFailureTests.aLentConnectWhoseSlotsCannotBeMadeFailsAloneAndLeavesItsChannelFree") {
            val (listener, handlers) = echoingOwnedListener()
            val shared = UdpSocket.bind(LENT_LOOPBACK, 0, bufferFactory = BufferFactory.network()).demultiplex(this)
            val loops = LoopSet(1)
            try {
                // Both on one loop: the owned connection started it, so only the lent io's slots need the failing memory.
                val owned = connectCore(listener.port, loops = loops)
                assertSharedPortEchoes(owned.connection.public(), 0)
                val at = QuicEndpoint(ResolvedAddress(LENT_LOOPBACK, IpFamily.V4), listener.port)
                // The entry a connectQuic over a channel runs, with memory whose datagram slots have no address.
                val failed =
                    runCatching { connectOverLent(shared.quic, at, "localhost", coreTestOptions, LENT_WAIT, NoSlotMemory(), loops) }
                assertTrue(failed.isFailure, "the connect without addressable slots failed")
                assertTrue(claimable(shared.quic), "the channel is free again")
                assertSharedPortEchoes(owned.connection.public(), 1)
                owned.connection.closeNow(QuicError.NoError)
            } finally {
                loops.close()
                handlers.cancel()
                listener.close()
                shared.close()
            }
        }
}
