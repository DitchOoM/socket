package com.ditchoom.socket

import com.ditchoom.socket.harness.WriteTimeoutContractTests
import kotlin.test.Test
import kotlin.test.fail

// TEMPORARY diagnostic for the Windows lane; removed before merge.
class TempSelectorWriteTimeoutProbe {
    @Test
    fun probe() {
        val savedAsync = useAsyncChannels
        val savedBlocking = useNioBlocking
        useAsyncChannels = false
        useNioBlocking = false
        val lines = mutableListOf<String>()
        try {
            repeat(15) { i ->
                val c = WriteTimeoutContractTests()
                listOf<Pair<String, () -> Unit>>(
                    "untilClosed" to { c.untilClosedWriteToNonDrainingPeerSuspends() },
                    "s2" to { c.boundedWriteToNonDrainingPeerTimesOut() },
                    "s3" to { c.boundedWriteTimeoutThrowsSocketTimeoutException() },
                    "s4" to { c.boundedWriteTimeoutClosesConnection() },
                ).forEach { (name, body) ->
                    val t0 = System.nanoTime()
                    val r = runCatching { body() }
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val busy = Thread.getAllStackTraces().keys.count { it.isAlive && it.state == Thread.State.RUNNABLE }
                    lines += "#$i $name ${ms}ms runnable=$busy " + (r.exceptionOrNull()?.let { "FAIL ${it.message}" } ?: "ok")
                }
            }
        } finally {
            useAsyncChannels = savedAsync
            useNioBlocking = savedBlocking
        }
        println("PROBE-RESULTS\n" + lines.joinToString("\n"))
        if (System.getProperty("os.name").startsWith("Windows")) fail("PROBE-RESULTS\n" + lines.joinToString("\n"))
    }
}
