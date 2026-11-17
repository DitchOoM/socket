package com.ditchoom.socket.mainthread

import com.ditchoom.socket.HostResolver
import com.ditchoom.socket.Resolution
import com.ditchoom.socket.ResolveFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Resolving a hostname through the public resolver, on the main-like thread, in a fresh JVM (no cache). */
class DnsMainThreadSafetyTest {
    @Test
    fun platformResolverResolvesLocalhost() =
        runOnMainLike {
            HostResolver.platform().resolve("localhost")
            assertOnMainLike("HostResolver.resolve")
        }

    @Test
    fun anUnknownNameIsATypedFailureAndNeverBlocksTheCaller() =
        runOnMainLike {
            val answer = HostResolver.platform().resolve("main-thread-unknown.lookup.invalid")
            assertOnMainLike("HostResolver.resolve of an unknown name")
            assertEquals(ResolveFailure.NotFound, assertIs<Resolution.Failed>(answer).why)
        }

    @Test
    fun cancellingALookupOnTheCallerNeverBlocksIt() =
        runOnMainLike {
            val lookup =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    HostResolver.platform().resolve("main-thread-cancelled.lookup.invalid")
                }
            lookup.cancelAndJoin()
            assertOnMainLike("cancelling HostResolver.resolve")
        }

    /** The control: the JDK lookup the resolver wraps does block this thread, so the passes above mean something. */
    @Test
    fun theJdkLookupItWrapsBlocksTheMainLikeThread() =
        assertBlocksTheMainLikeThread { InetAddress.getAllByName("main-thread-control.lookup.invalid") }
}
