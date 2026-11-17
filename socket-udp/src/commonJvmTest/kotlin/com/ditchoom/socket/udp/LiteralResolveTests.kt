package com.ditchoom.socket.udp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An address literal needs no lookup, so resolving one stays on the caller's thread: a hop to `Dispatchers.IO` and back
 * per resolve is what a herd of QUIC connects (two resolves each) paid in IO worker wake-ups. A host name still resolves
 * off the caller's thread.
 */
class LiteralResolveTests {
    /** Counts the times a coroutine on it had to be dispatched: every resumption after a hop elsewhere is one. */
    private class Counting : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            dispatches.incrementAndGet()
            Dispatchers.Default.dispatch(context, block)
        }
    }

    @Test
    fun anAddressLiteralResolvesWithoutLeavingTheCallersDispatcher() =
        runBlocking {
            val counting = Counting()
            withContext(counting) {
                val before = counting.dispatches.get()
                for (literal in listOf("127.0.0.1", "0.0.0.0", "::1", "::", "fe80::1")) {
                    UdpSocket.resolve(literal, 443)
                }
                assertEquals(0, counting.dispatches.get() - before, "dispatches back to the caller while resolving five literals")
            }
        }

    @Test
    fun aHostNameStillResolvesOffTheCallersThread() =
        runBlocking {
            val counting = Counting()
            withContext(counting) {
                val before = counting.dispatches.get()
                UdpSocket.resolve("localhost", 443)
                assertTrue(counting.dispatches.get() - before >= 1, "a host name's lookup came back to the caller by a dispatch")
            }
        }
}
