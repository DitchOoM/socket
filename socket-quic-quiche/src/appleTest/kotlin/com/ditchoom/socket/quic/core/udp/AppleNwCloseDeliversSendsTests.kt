package com.ditchoom.socket.quic.core.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.ApplePosixUdp
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.edge.NwConnector
import com.ditchoom.socket.quic.core.edge.connect
import com.ditchoom.socket.quic.core.edge.nwSocketIo
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.runBlocking
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A Network.framework send completes later, on its own queue. A socket closed right after its last send, as a client
 * closed with an error is once its linger ends, still delivers every datagram it was handed: the CONNECTION_CLOSE a
 * client sends when it refuses the server's certificate is such a last send.
 */
class AppleNwCloseDeliversSendsTests {
    @Test
    fun everyDatagramHandedToSendBeforeTheCloseLeaves() =
        runBlocking {
            val receiver = ApplePosixUdp.openBoundLoopbackSocket()
            try {
                ApplePosixUdp.setReceiveTimeout(receiver, RECEIVE_WAIT_MILLIS)
                val port = ApplePosixUdp.boundPortOf(receiver)
                var arrived = 0
                repeat(ROUNDS) { arrived += sendThenClose(port, receiver) }
                assertEquals(ROUNDS * DATAGRAMS, arrived, "a datagram handed to the send before the close was dropped")
            } finally {
                close(receiver)
            }
        }

    /** Hands [DATAGRAMS] datagrams to a fresh connection's send, closes it at once, and counts what reached [receiver]. */
    private suspend fun sendThenClose(
        port: Int,
        receiver: Int,
    ): Int {
        val io = nwSocketIo(NwConnector().connect("127.0.0.1", port)) {}.io
        val storage = assertIs<Addressed.Native>(NativeBytes.of(BufferFactory.network().allocate(DATAGRAMS * SIZE))).bytes
        try {
            val batch = SendBatch(storage, BatchLimits(DATAGRAMS, SIZE))
            repeat(DATAGRAMS) { batch.commit(SIZE, SendTarget.ConnectedPeer) }
            assertIs<SendBatchResult.AllTaken>(io.send(batch))
            io.close()
        } finally {
            storage.free()
        }
        val into = ByteArray(SIZE * 2)
        var got = 0
        while (ApplePosixUdp.receiveFrom(receiver, into) > 0) got++
        return got
    }

    private companion object {
        const val ROUNDS = 20
        const val DATAGRAMS = NwWire.MAX_IN_FLIGHT
        const val SIZE = 1200
        const val RECEIVE_WAIT_MILLIS = 200L
    }
}
