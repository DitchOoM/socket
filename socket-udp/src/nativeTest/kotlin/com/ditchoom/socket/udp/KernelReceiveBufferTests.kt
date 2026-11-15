package com.ditchoom.socket.udp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** `requestKernelReceiveBuffer` on a real native socket; the assertion is shared with the JVM suite. */
class KernelReceiveBufferTests {
    @Test
    fun aKernelReceiveBufferRequestIsAnsweredWithWhatTheHostGave() =
        runBlocking {
            withTimeout(20_000) { assertAKernelReceiveBufferRequestIsAnsweredWithWhatTheHostGave() }
        }
}
