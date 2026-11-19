@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.PathDelayRelay
import com.ditchoom.socket.quic.QuicDatagramTooLarge
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.loop.LoopSet
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The datagram room a client reads the moment its connection is established is the room quiche sends with. With path
 * MTU discovery, quiche sizes the packets it sends during the handshake to the current MTU and those after it to its
 * probe size, and the datagram room follows. A server flight that arrives one datagram per turn has the client answer
 * between them, so the turn that completes the handshake starts with the handshake's size; the relay spaces datagrams
 * [SPACING] apart for that, and its [ONE_WAY] delay keeps the client's next turn behind the caller's first read.
 */
class CoreDatagramRoomTests {
    private val loops = LoopSet(1)
    private val on = coreTestOptions.copy(datagrams = DatagramOptions())

    @AfterTest
    fun closeLoops() = loops.close()

    @Test
    fun theRoomReadWhenEstablishedIsTheRoomQuicheSendsWith() =
        coreTest("CoreDatagramRoomTests.theRoomReadWhenEstablishedIsTheRoomQuicheSendsWith") {
            withCoreServer(options = on, tls = coreTlsConfig(TestCertificate.Rsa2048), onConnection = { delay(HOLD) }) { server ->
                PathDelayRelay(InetSocketAddress("127.0.0.1", server.port), ONE_WAY, SPACING).use { relay ->
                    val client = connectCore(relay.port, loops = loops, options = on)
                    try {
                        val channel = client.connection.datagramChannel()
                        val max = channel.maxWritableSize
                        val tooBig = BufferFactory.network().allocate(max + 1)
                        repeat(max + 1) { tooBig.writeByte(0) }
                        tooBig.resetForRead()
                        val refusal =
                            try {
                                assertFailsWith<QuicDatagramTooLarge>("$max bytes were said to fit") { channel.send(tooBig) }
                            } finally {
                                tooBig.freeIfNeeded()
                            }
                        assertEquals(max, refusal.maxBytes, "the refusal names the room the caller read")
                    } finally {
                        client.connection.close(QuicError.NoError)
                    }
                }
            }
        }

    private companion object {
        val ONE_WAY = 100.milliseconds
        val SPACING = 30.milliseconds
        val HOLD = 10.seconds
    }
}
