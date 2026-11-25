package com.ditchoom.socket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * A name lookup that never answers must not hold its caller once the caller is cancelled.
 *
 * `getaddrinfo` and `InetAddress.getAllByName` block their thread until the system resolver answers and cannot be
 * interrupted. Each lookup here blocks its thread the same way, on the platform's own resolver threads
 * ([blockingLookupResolver]), until the test lets it go, and the test lets it go only after the cancelled caller has
 * finished. A caller whose cancellation waited for its lookup could therefore never finish: the outcome is an ordering,
 * not a timing, and the bound only turns that deadlock into a failure.
 *
 * Shared by the JVM, Linux and Apple test source sets: those are the resolvers that block a thread. (On JS the lookup
 * is a callback and there is no thread to hold.)
 */
class BlockingHostResolverTests {
    @Test
    fun aCancelledCallerFinishesWhileItsLookupIsStillInsideTheOs() =
        runBlocking {
            val lookups = HeldLookups()
            val resolver = blockingLookupResolver(lookups::lookup)
            try {
                val caller = launch(Dispatchers.Default) { resolver.resolve("held.invalid") }
                withTimeout(BOUND) { assertEquals("held.invalid", lookups.entered.receive()) }
                caller.cancel()
                joinOrFail(caller, "a caller cancelled while its lookup is held")
            } finally {
                lookups.release.complete(Unit)
            }
            // The abandoned lookup still runs to its end, so whatever it allocated is freed.
            withTimeout(BOUND) { assertEquals("held.invalid", lookups.finished.receive()) }
        }

    @Test
    fun aCancelledCallerQueuedBehindBusyLookupsNeverStartsOne() =
        runBlocking {
            val lookups = HeldLookups()
            val resolver = blockingLookupResolver(lookups::lookup)
            val busy = List(RESOLVER_PARALLELISM) { i -> "busy-$i.invalid" }
            val busyCallers = busy.map { host -> launch(Dispatchers.Default) { resolver.resolve(host) } }
            try {
                withTimeout(BOUND) { assertEquals(busy.toSet(), List(busy.size) { lookups.entered.receive() }.toSet()) }
                // Undispatched, so the caller has asked for its lookup and waits for it when it is cancelled.
                val queued = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { resolver.resolve("queued.invalid") }
                queued.cancel()
                joinOrFail(queued, "a caller cancelled while its lookup is queued for a resolver thread")
            } finally {
                lookups.release.complete(Unit)
            }
            busyCallers.forEach { joinOrFail(it, "a caller whose lookup was let go") }
            // Lookups run in the order they were asked for, so this one starts only after the cancelled one had its turn.
            assertEquals(Resolution.NoAddress("after.invalid"), withTimeout(BOUND) { resolver.resolve("after.invalid") })
            assertEquals("after.invalid", lookups.entered.receive(), "the cancelled caller's lookup started")
            assertTrue(lookups.entered.tryReceive().isFailure, "a lookup started that nobody asked for")
        }

    @Test
    fun anAnswerIsReturned() =
        runBlocking {
            val resolver = blockingLookupResolver { host -> Resolution.NoAddress(host) }
            assertEquals(Resolution.NoAddress("example.test"), resolver.resolve("example.test"))
        }

    /** Lookups that block their thread, as the system resolver does, until [release]. */
    private class HeldLookups {
        val entered = Channel<String>(Channel.UNLIMITED)
        val finished = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()

        fun lookup(host: String): Resolution {
            entered.trySend(host)
            runBlocking { release.await() }
            finished.trySend(host)
            return Resolution.NoAddress(host)
        }
    }

    private suspend fun joinOrFail(
        job: Job,
        what: String,
    ) {
        try {
            withTimeout(BOUND) { job.join() }
        } catch (_: TimeoutCancellationException) {
            fail("$what was still waiting after $BOUND")
        }
    }

    private companion object {
        /** Ends a wait that a correct resolver never makes; a passing run never comes near it. */
        val BOUND = 10.seconds
    }
}
