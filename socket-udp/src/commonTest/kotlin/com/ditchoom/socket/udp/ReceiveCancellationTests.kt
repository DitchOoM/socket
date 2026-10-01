@file:OptIn(ExperimentalDatagramApi::class)

package com.ditchoom.socket.udp

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.flow.DatagramReadResult
import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A receive parked on a socket nobody is sending to ends when its coroutine is cancelled, with the
 * socket still open — and the socket still serves the next receive.
 *
 * A socket is not always its reader's to close: a connection riding a port another party owns cancels
 * its reader and must not close the socket, so a receive that only a close or a datagram could end
 * would hold that connection's teardown until the peer happened to send again.
 */
class ReceiveCancellationTests {
    @Test
    fun aParkedReceiveEndsWhenItsCoroutineIsCancelled() =
        runTest {
            withContext(Dispatchers.Default) {
                val socket = UdpSocket.bind(localHost = "127.0.0.1", bufferFactory = BufferFactory.deterministic())
                try {
                    val parked = async(start = CoroutineStart.DEFAULT) { socket.receive() }
                    // Long enough that the receive is inside the platform's wait; the other ordering is
                    // a receive cancelled before it waits, which must end too.
                    delay(200.milliseconds)
                    parked.cancel()
                    val ended = withTimeoutOrNull(2.seconds) { runCatching { parked.await() } }
                    assertTrue(ended != null, "a cancelled receive on an open socket was still parked 2s later")
                    assertTrue(socket.isOpen, "cancelling a receive must not close the socket")

                    val sender = UdpSocket.bind(localHost = "127.0.0.1", bufferFactory = BufferFactory.deterministic())
                    try {
                        val payload = BufferFactory.deterministic().allocate(4)
                        payload.writeInt(0x51554943)
                        payload.resetForRead()
                        sender.send(payload, UdpSocket.resolve("127.0.0.1", socket.localAddress.port))
                        val next = withTimeoutOrNull(2.seconds) { socket.receive() }
                        val received =
                            assertIs<DatagramReadResult.Received>(next, "the socket must still receive after a cancelled receive")
                        assertEquals(0x51554943, received.datagram.payload.readInt())
                        received.datagram.payload.freeNativeMemory()
                    } finally {
                        sender.close()
                    }
                } finally {
                    socket.close()
                }
            }
        }
}
