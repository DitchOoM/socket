package com.ditchoom.socket.udp

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The buffer #328 capability contract on real Node `dgram` sockets, where the expected answer is
 * `false`: `dgram.send` takes a `Uint8Array` and there is no native-address concept on this platform
 * to require. The assertion is shared with the JVM and native suites in
 * [assertNativeMemoryRequirementMatchesSendPath]; only the runner is per-platform (Node has no
 * `runBlocking`, so the test returns the Promise for Mocha to await).
 */
@OptIn(DelicateCoroutinesApi::class)
class NativeMemoryRequirementTests {
    @Test
    fun nativeMemoryRequirementMatchesSendPath() =
        GlobalScope.promise {
            withTimeout(20_000) { assertNativeMemoryRequirementMatchesSendPath() }
        }
}
