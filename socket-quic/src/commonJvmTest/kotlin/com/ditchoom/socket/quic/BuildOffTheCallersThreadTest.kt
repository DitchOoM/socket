package com.ditchoom.socket.quic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [buildOffTheCallersThread] owns what it built until the caller receives it: a caller cancelled while
 * the build is still running never gets the result, so the result must be handed to `onAbandoned`
 * (which closes it) instead of leaking.
 */
class BuildOffTheCallersThreadTest {
    @Test
    fun aResultBuiltAfterTheCallerWasCancelledIsAbandonedNotLeaked() =
        runBlocking {
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val abandoned = AtomicReference<String?>(null)
            val call =
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    buildOffTheCallersThread(onAbandoned = { abandoned.set(it) }) {
                        started.countDown()
                        assertTrue(finish.await(10, TimeUnit.SECONDS), "the test never let the build finish")
                        "built"
                    }
                }
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) }, "the build never started")
            call.cancel()
            finish.countDown()
            assertFailsWith<CancellationException> { call.await() }
            assertEquals("built", abandoned.get())
        }

    @Test
    fun aResultTheCallerReceivesIsNotAbandoned() =
        runBlocking {
            val abandoned = AtomicReference<String?>(null)
            assertEquals("built", buildOffTheCallersThread(onAbandoned = { abandoned.set(it) }) { "built" })
            assertNull(abandoned.get())
        }
}
