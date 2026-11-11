package com.ditchoom.socket.quic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trust evaluation's Core Foundation objects are held by the call into Security.framework and by
 * the callback it delivers. Releasing them when only one of the two is done freed the trust object under
 * the other (a segmentation fault inside `SecTrustEvaluateAsyncWithError`), so the release has to wait
 * for both and happen once.
 */
class TwoPartyHoldTests {
    @Test
    fun nothingIsReleasedWhileOnePartyStillHolds() {
        val released = AtomicInt(0)
        val hold = TwoPartyHold { released.incrementAndGet() }
        hold.letGo()
        assertEquals(0, released.value, "one party letting go must not release what the other still uses")
        hold.letGo()
        assertEquals(1, released.value, "the last party letting go releases")
    }

    @Test
    fun twoPartiesLettingGoAtOnceReleaseExactlyOnce() =
        runTest {
            withContext(Dispatchers.Default) {
                repeat(20_000) {
                    val released = AtomicInt(0)
                    val hold = TwoPartyHold { released.incrementAndGet() }
                    listOf(async { hold.letGo() }, async { hold.letGo() }).awaitAll()
                    assertEquals(1, released.value, "released ${released.value} times")
                }
            }
        }
}
