package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/**
 * The buffer #328 capability contract on real native sockets (Linux io_uring / Apple NW + POSIX),
 * where the expected answer is `true`: every one of those send paths resolves the payload's
 * `nativeMemoryAccess` and errors outright without it, while `BufferFactory.Default` on Kotlin/Native
 * has no native address. This is the platform pair the flag was added for. The assertion is shared
 * with the JVM and Node suites in [assertNativeMemoryRequirementMatchesSendPath].
 */
class NativeMemoryRequirementTests {
    @Test
    fun nativeMemoryRequirementMatchesSendPath() =
        runBlocking {
            withTimeout(20_000) { assertNativeMemoryRequirementMatchesSendPath() }
        }
}
