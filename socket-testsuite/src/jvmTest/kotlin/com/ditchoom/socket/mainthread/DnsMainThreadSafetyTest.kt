package com.ditchoom.socket.mainthread

import com.ditchoom.socket.HostResolver
import kotlin.test.Test

/** Resolving a hostname through the public resolver, on the main-like thread, in a fresh JVM (no cache). */
class DnsMainThreadSafetyTest {
    @Test
    fun platformResolverResolvesLocalhost() =
        runOnMainLike {
            HostResolver.platform().resolve("localhost")
            assertOnMainLike("HostResolver.resolve")
        }
}
