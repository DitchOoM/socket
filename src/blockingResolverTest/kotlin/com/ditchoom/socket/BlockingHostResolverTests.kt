@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A name lookup that never answers must not hold its caller once the caller is cancelled.
 *
 * `getaddrinfo` and `InetAddress.getAllByName` block their thread until the system resolver answers and
 * cannot be interrupted, so a runner whose DNS server is slow kept a QUIC connect, and every `withTimeout`
 * around it, waiting for as long as the resolver's retries took. The lookup here holds its thread until
 * the test lets it go, which is exactly that, without needing a broken DNS server.
 *
 * Shared by the JVM, Linux and Apple test source sets: those are the resolvers that block a thread. (On
 * JS the lookup is a callback and there is no thread to hold.)
 */
class BlockingHostResolverTests {
    @Test
    fun aCancelledResolveReturnsWithoutWaitingForTheLookup() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val released = AtomicBoolean(false)
            val finished = CompletableDeferred<Unit>()
            val resolver =
                BlockingHostResolver(Dispatchers.IO) { host ->
                    entered.complete(Unit)
                    // A thread inside the system resolver: it answers when it answers.
                    while (!released.load()) Unit
                    finished.complete(Unit)
                    Resolution.NoAddress(host)
                }
            // Lets the lookup go after WATCHDOG whatever happens, so a resolver whose caller waits for the
            // lookup fails this test with the time it waited instead of hanging it.
            val watchdog =
                launch(Dispatchers.Default) {
                    delay(WATCHDOG)
                    released.store(true)
                }
            val started = TimeSource.Monotonic.markNow()
            try {
                val answer =
                    withTimeoutOrNull(BOUND) {
                        resolver.resolve("example.invalid")
                    }
                val waited = started.elapsedNow()
                assertNull(answer, "the lookup never answered, so there is nothing to return")
                assertTrue(entered.isCompleted, "the lookup had started before the caller gave up")
                assertTrue(
                    waited < BOUND + GRACE,
                    "the caller was cancelled at $BOUND but waited $waited: its cancellation waited for the lookup",
                )
            } finally {
                released.store(true)
                watchdog.cancel()
            }
            // The abandoned lookup still runs to its end, so whatever it allocated is freed.
            withTimeout(5.seconds) { finished.await() }
        }

    @Test
    fun anAnswerIsReturned() =
        runBlocking {
            val resolver = BlockingHostResolver(Dispatchers.IO) { host -> Resolution.NoAddress(host) }
            assertEquals(Resolution.NoAddress("example.test"), resolver.resolve("example.test"))
        }

    private companion object {
        val BOUND = 100.milliseconds
        val GRACE = 2.seconds
        val WATCHDOG = 5.seconds
    }
}
