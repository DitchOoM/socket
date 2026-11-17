package com.ditchoom.socket.udp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An address literal needs no lookup, so resolving one on Kotlin/Native stays on the caller's dispatcher: `getaddrinfo`
 * with `AI_NUMERICHOST` never asks DNS. A hop to `Dispatchers.Default` and back per resolve is what each QUIC connect
 * paid twice.
 */
class NativeLiteralResolveTests {
    /** Counts the times a coroutine on it had to be dispatched: every resumption after a hop elsewhere is one. */
    private class Counting : CoroutineDispatcher() {
        val dispatches = AtomicInt(0)

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
                val before = counting.dispatches.value
                for (literal in listOf("127.0.0.1", "0.0.0.0", "::1", "::")) {
                    UdpSocket.resolve(literal, 443)
                }
                assertEquals(0, counting.dispatches.value - before, "dispatches back to the caller while resolving four literals")
            }
        }
}
