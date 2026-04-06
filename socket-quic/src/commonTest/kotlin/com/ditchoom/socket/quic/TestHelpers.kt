package com.ditchoom.socket.quic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * QUIC test runner with 15s timeout. Uses runBlocking for real I/O.
 */
fun runQuicTest(block: suspend CoroutineScope.() -> Unit) =
    runBlocking {
        withTimeout(15.seconds) { block() }
    }
