package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.loop.LoopIo
import com.ditchoom.socket.quic.core.testing.assumeNativeStack
import com.ditchoom.socket.quic.core.udp.GroSetup
import com.ditchoom.socket.quic.core.udp.NativeIo
import com.ditchoom.socket.quic.core.udp.Receiving
import com.ditchoom.socket.quic.core.udp.SocketBuffers
import com.ditchoom.socket.quic.core.udp.SocketPeer
import com.ditchoom.socket.quic.core.udp.toPathKey
import com.ditchoom.socket.quic.loadNetShim
import com.ditchoom.socket.quic.network
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The sockets a core client and server open ask for `UDP_GRO` by their [UdpBatching]: the batchings that segment do,
 * and a kernel that does not grant it says so as a typed setup, never as a silent per-datagram socket.
 */
class CoreSocketGroTests {
    private val memory = EdgeMemory(BufferFactory.network())

    @AfterTest
    fun freeAll() = memory.freeAll()

    @Test
    fun newestAsksForGroAndSaysWhatTheKernelGranted() = segmentingBatchingAsksForGro(UdpBatching.Newest)

    @Test
    fun publicCallsOnlyAsksForGroToo() = segmentingBatchingAsksForGro(UdpBatching.PublicCallsOnly)

    @Test
    fun theBatchingsThatDoNotSegmentDoNotAskForGro() {
        listOf(UdpBatching.WithoutSegments, UdpBatching.OneDatagramPerCall).forEach { batching ->
            val socket = openNative(batching)
            try {
                assertEquals(GroSetup.NotRequested, socket.socket.gro, "$batching")
                assertEquals(Receiving.PerDatagram, socket.socket.receiving, "$batching")
            } finally {
                close(socket)
            }
        }
    }

    private fun segmentingBatchingAsksForGro(batching: UdpBatching) {
        val socket = openNative(batching)
        try {
            when (val gro = socket.socket.gro) {
                is GroSetup.Enabled -> assertEquals(Receiving.Coalesced, socket.socket.receiving)
                // A kernel before 5.0, or a platform without UDP_GRO, says so; it is never a silent fallback.
                is GroSetup.Refused, is GroSetup.Unavailable -> assertEquals(Receiving.PerDatagram, socket.socket.receiving)
                is GroSetup.NotRequested -> error("$batching never asked for UDP_GRO: $gro")
            }
        } finally {
            close(socket)
        }
    }

    private fun close(socket: StackSocket.Native) = socket.socket.cell.close { fd -> socket.loop.shim.close(fd) }

    private fun openNative(batching: UdpBatching): StackSocket.Native {
        assumeNativeStack()
        val io =
            LoopIo.Native(
                loadNetShim(),
                memory.native(NativeIo.RECV_RECORD * NativeIo.MAX_RECEIVE),
                listOf(memory.native(NativeIo.SEND_RECORD * CLIENT_BATCH)),
            )
        val bind = InetSocketAddress("::1", 0).toPathKey()
        return assertIs<StackSocket.Native>(
            openStackSocket(io, memory, bind, SocketPeer.Unconnected, SocketBuffers.Default, batching, CLIENT_BATCH),
        )
    }
}
