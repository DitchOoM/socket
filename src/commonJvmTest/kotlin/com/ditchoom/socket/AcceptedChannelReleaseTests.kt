package com.ditchoom.socket

import com.ditchoom.socket.nio2.util.AcceptCompletionHandler
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.AsynchronousSocketChannel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JDK hands an accepted channel to [AcceptCompletionHandler.completed] on its own thread. If
 * the accepting coroutine was cancelled first, `resume` is silently dropped, so the accepted
 * channel (a live, connected socket the peer thinks is being served) must be released right there:
 * nothing else holds a reference to it.
 */
class AcceptedChannelReleaseTests {
    @Test
    fun anAcceptedChannelArrivingAfterTheAcceptorWasCancelledIsClosed() =
        runBlocking {
            val parked = ParkedAcceptor()
            val acceptor =
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<AsynchronousSocketChannel> { cont -> parked.set(cont) }
                }
            val cont = parked.get()
            acceptor.cancel()

            val accepted = AsynchronousSocketChannel.open()
            assertTrue(accepted.isOpen)
            AcceptCompletionHandler(cont).completed(accepted, cont)

            assertFalse(accepted.isOpen, "the accepted channel was dropped by a cancelled acceptor and never closed")
        }

    @Test
    fun anAcceptedChannelDeliveredToALiveAcceptorStaysOpen() =
        runBlocking {
            val parked = ParkedAcceptor()
            val acceptor =
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<AsynchronousSocketChannel> { cont -> parked.set(cont) }
                }
            val cont = parked.get()
            val accepted = AsynchronousSocketChannel.open()
            AcceptCompletionHandler(cont).completed(accepted, cont)

            assertTrue(acceptor.await() === accepted)
            assertTrue(accepted.isOpen, "a delivered channel belongs to the acceptor and must not be closed")
            accepted.close()
        }

    private class ParkedAcceptor {
        private var value: CancellableContinuation<AsynchronousSocketChannel>? = null

        fun set(c: CancellableContinuation<AsynchronousSocketChannel>) {
            value = c
        }

        fun get(): CancellableContinuation<AsynchronousSocketChannel> = checkNotNull(value)
    }
}
