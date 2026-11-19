package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.udp.DatagramBatchIo
import com.ditchoom.socket.quic.core.udp.SendBatch
import com.ditchoom.socket.quic.core.udp.SendBatchResult
import com.ditchoom.socket.quic.core.udp.UdpError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Client connections on one loop collect their output into the loop's one batch, a serve at a time. A socket that
 * refuses a send keeps its datagrams out of that batch, so the next connection served never sends them on its own
 * socket nor overwrites them: every socket sends only its own connection's datagrams, and every byte arrives.
 */
class SharedLoopBatchTests {
    private val loops = LoopSet(1)

    @AfterTest
    fun closeLoops() = loops.close()

    /** Records the destination connection id of every datagram it sends; every other send is refused as a full socket. */
    private class Refusing(
        private val inner: DatagramBatchIo,
        private val refuses: Boolean,
    ) : DatagramBatchIo by inner {
        val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val refused = AtomicInteger()
        private val calls = AtomicInteger()

        override fun send(batch: SendBatch): SendBatchResult {
            if (refuses && calls.incrementAndGet() % 2 == 0) {
                refused.incrementAndGet()
                return SendBatchResult.Partial(0, UdpError.WouldBlock)
            }
            for (n in 0 until batch.pending) ids.add(destinationOf(batch, batch.offsetOf(n)))
            return inner.send(batch)
        }

        /** The first packet's destination connection id: a long header names its length, a short one is the server's. */
        private fun destinationOf(
            batch: SendBatch,
            at: Int,
        ): String {
            val bytes = batch.storage
            val long = bytes.byteAt(at) and LONG_HEADER != 0
            val start = if (long) at + LONG_DCID else at + 1
            val length = if (long) bytes.byteAt(at + LONG_DCID_LENGTH) else SERVER_ID_BYTES
            return (0 until length).joinToString("") { "%02x".format(bytes.byteAt(start + it)) }
        }
    }

    @Test
    fun aSocketThatRefusesKeepsItsDatagramsOutOfTheBatchItsLoopSharesWithTheNextConnection() =
        runBlocking {
            withCoreEchoServer { server ->
                val refusing = ArrayList<Refusing>()
                val clients =
                    List(CONNECTIONS) { i ->
                        connectCore(server.port, loops = loops, wrapIo = { io ->
                            Refusing(io, refuses = i % 2 == 0).also { refusing.add(it) }
                        })
                    }
                try {
                    val payloads = List(CONNECTIONS) { Random(it).nextBytes(PAYLOAD) }
                    val echoed =
                        clients.indices
                            .map { i -> async(Dispatchers.Default) { echoOver(clients[i].connection.openStream(), payloads[i]) } }
                            .awaitAll()
                    for (i in clients.indices) assertContentEquals(payloads[i], echoed[i], "connection $i's bytes all came back")
                    assertTrue(refusing.filterIndexed { i, _ -> i % 2 == 0 }.all { it.refused.get() > 0 }, "the refusing sockets refused")
                    for (i in refusing.indices) {
                        for (j in refusing.indices) {
                            if (i == j) continue
                            val shared = refusing[i].ids.intersect(refusing[j].ids)
                            assertTrue(shared.isEmpty(), "socket $i sent datagrams of socket $j's connection: $shared")
                        }
                    }
                } finally {
                    clients.forEach { it.connection.close(QuicError.NoError) }
                }
            }
        }

    private companion object {
        const val CONNECTIONS = 4
        const val PAYLOAD = 512 * 1024
        const val LONG_HEADER = 0x80
        const val LONG_DCID_LENGTH = 5
        const val LONG_DCID = 6
        const val SERVER_ID_BYTES = 16
    }
}
