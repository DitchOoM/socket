@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.flow.ConnectedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramRefusal
import com.ditchoom.socket.quic.DatagramSend
import com.ditchoom.socket.quic.QuicDatagramRefused
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.network
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A datagram send over a full send queue is refused at once, typed, and never waits for the queue to drain: the
 * sender that wants room asks for it ([DatagramSend.NotSent.QueueFull.awaitRoom]) and is woken once the queue has drained.
 */
class CoreDatagramFullQueueTests {
    private val options =
        QuicOptions(
            alpnProtocols = listOf("test"),
            verifyPeer = false,
            idleTimeout = 30.seconds,
            enablePmtuDiscovery = false,
            datagrams = DatagramOptions(sendQueueLen = DEPTH),
        )

    @Test
    fun aSendOverAFullQueueIsRefusedAtOnceAndRoomIsAwaitedNotPolled() =
        VirtualPair.with(options) { pair ->
            pair.accept()
            val channel = pair.client.connection.coreDatagrams
            val payload = payloadOf(SMALL)
            // The loop does not run while the test sends, so nothing drains the queue it fills.
            repeat(DEPTH) { assertSame(DatagramSend.Sent, channel.send(payload), "send $it") }

            val suspended =
                async(start = CoroutineStart.UNDISPATCHED) { runCatching { (channel as ConnectedDatagramChannel).send(payload) } }
            assertTrue(suspended.isCompleted, "the send did not park for a queue that cannot drain while it waits")
            val refusal = assertIs<QuicDatagramRefused>(suspended.await().exceptionOrNull())
            assertEquals(DatagramRefusal.QueueFull, refusal.refusal)

            val full = assertIs<DatagramSend.NotSent.QueueFull>(channel.send(payload))
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { full.awaitRoom() }
            pair.runFor(100.milliseconds)
            assertTrue(waiting.isCompleted, "the drained queue woke the waiter")
            assertSame(DatagramSend.Sent, channel.send(payload), "the room the waiter was woken for is there")
            payload.freeIfNeeded()
        }

    private fun payloadOf(size: Int): PlatformBuffer =
        BufferFactory.network().allocate(size).apply {
            repeat(size) { writeByte(it.toByte()) }
            resetForRead()
        }

    private companion object {
        const val DEPTH = 64
        const val SMALL = 64
    }
}
