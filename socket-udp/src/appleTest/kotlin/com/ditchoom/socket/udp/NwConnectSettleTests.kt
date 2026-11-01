package com.ditchoom.socket.udp

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NW delivers state changes on a concurrent queue, so one connect's handler can run on several threads
 * at once. Each such call that reaches a verdict must not resume the connect again: Kotlin/Native
 * aborts the process on a second resume (three iPhone walk crashes, 10-30/10-31).
 */
class NwConnectSettleTests {
    @Test
    fun concurrentVerdictsResumeTheConnectExactlyOnce() =
        runBlocking {
            val escaped = AtomicReference(listOf<Throwable>())
            val callbacks =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default +
                        CoroutineExceptionHandler { _, t ->
                            while (true) {
                                val seen = escaped.value
                                if (escaped.compareAndSet(seen, seen + t)) break
                            }
                        },
                )
            val failed = NwConnectVerdict.Failed(UdpConnectError.Unreachable(ERRNO_NOT_SURFACED))
            try {
                repeat(ROUNDS) { round ->
                    val outcome =
                        runCatching {
                            suspendCancellableCoroutine<Unit> { continuation ->
                                val settle = NwConnectSettle(continuation)
                                repeat(CALLBACK_THREADS) { callbacks.launch { settle.on(failed) } }
                            }
                        }
                    assertTrue(outcome.exceptionOrNull() is UdpConnectException, "round $round: $outcome")
                }
            } finally {
                callbacks.cancel()
            }
            assertEquals(emptyList(), escaped.value.map { it::class.simpleName + ": " + it.message }.take(3))
        }

    private companion object {
        const val ROUNDS = 2000
        const val CALLBACK_THREADS = 4
    }
}
