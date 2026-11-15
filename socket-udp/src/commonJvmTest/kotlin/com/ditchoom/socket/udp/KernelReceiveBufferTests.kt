package com.ditchoom.socket.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

/** `requestKernelReceiveBuffer` on a real NIO socket; the assertion is shared with the native suite. */
class KernelReceiveBufferTests {
    @Test
    fun aKernelReceiveBufferRequestIsAnsweredWithWhatTheHostGave() =
        runBlocking(Dispatchers.IO) {
            withTimeout(20_000) { assertAKernelReceiveBufferRequestIsAnsweredWithWhatTheHostGave() }
        }
}
