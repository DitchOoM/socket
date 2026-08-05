package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The buffer #328 capability contract on real JVM/Android NIO sockets, where the expected answer is
 * `false`: `DatagramChannel.send` takes a heap `ByteBuffer` quite happily and copies it internally.
 * The assertion is shared with the Node and native suites in
 * [assertNativeMemoryRequirementMatchesSendPath]; only the runner is per-platform.
 */
class NativeMemoryRequirementTests {
    @Test
    fun nativeMemoryRequirementMatchesSendPath() =
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) { assertNativeMemoryRequirementMatchesSendPath() }
        }
}
