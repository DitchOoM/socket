package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.ExperimentalDatagramApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * An address literal needs no lookup, so resolving one stays on the caller's thread: a hop to `Dispatchers.IO` and back
 * per resolve is what a herd of QUIC connects (two resolves each) paid in IO worker wake-ups. A host name still resolves
 * off the caller's thread.
 *
 * The thread a lookup runs on is read inside the lookup itself. Counting the dispatches back to the caller instead
 * misses a hop whose result is ready before the caller has suspended: that return makes no dispatch.
 */
@OptIn(ExperimentalDatagramApi::class)
class LiteralResolveTests {
    /** The thread each of [hosts] is looked up on, resolved from a caller that runs on one thread of its own. */
    private fun lookupThreads(hosts: List<String>): Pair<Thread, List<Thread>> {
        val callerExecutor = Executors.newSingleThreadExecutor { Thread(it, "literal-resolve-caller") }
        try {
            return runBlocking {
                withContext(callerExecutor.asCoroutineDispatcher()) {
                    val seen = AtomicReference<Thread>()
                    val threads =
                        hosts.map { host ->
                            JvmSocketAddressResolver.resolveWith(host, 443) {
                                seen.set(Thread.currentThread())
                                InetAddress.getByName(it)
                            }
                            seen.get()
                        }
                    Thread.currentThread() to threads
                }
            }
        } finally {
            callerExecutor.shutdown()
        }
    }

    @Test
    fun anAddressLiteralIsLookedUpOnTheCallersThread() {
        val (caller, lookups) = lookupThreads(listOf("127.0.0.1", "0.0.0.0", "::1", "::", "fe80::1"))
        lookups.forEach { assertEquals(caller, it, "a literal's lookup left the caller's thread") }
    }

    @Test
    fun aHostNameIsLookedUpOffTheCallersThread() {
        val (caller, lookups) = lookupThreads(listOf("localhost"))
        lookups.forEach { assertNotEquals(caller, it, "a host name's lookup ran on the caller's thread") }
    }
}
