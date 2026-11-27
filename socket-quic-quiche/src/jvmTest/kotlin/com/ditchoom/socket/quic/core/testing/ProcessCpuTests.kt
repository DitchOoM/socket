package com.ditchoom.socket.quic.core.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [ProcessCpuSampler] names the process that burned the CPU across a window, and reads both hosts' `ps` time formats. */
class ProcessCpuTests {
    @Test
    fun psTimeReadsAsMillisecondsOnMacOsAndLinux() {
        assertEquals(37 * 60_000L + 45_380, ProcessCpuSampler.cpuMillisOf("37:45.38"), "macOS minutes:seconds")
        assertEquals(3_723_000L, ProcessCpuSampler.cpuMillisOf("01:02:03"), "Linux hours:minutes:seconds")
        assertEquals(86_400_000L + 3_723_000, ProcessCpuSampler.cpuMillisOf("1-01:02:03"), "Linux days-hours:minutes:seconds")
    }

    @Test
    fun aJvmSpinningAThreadIsAmongTheBusiestProcesses() {
        val sampler = ProcessCpuSampler.start(100)
        Thread.sleep(150)
        val from = System.nanoTime()
        val until = from + SPIN_NANOS
        while (System.nanoTime() < until) Thread.onSpinWait()
        val to = System.nanoTime()
        val processes = sampler.stop().across(from, to)
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertIs<Processes.Unavailable>(processes, "Windows has no ps")
            return
        }
        val seen = assertIs<Processes.Seen>(processes, "$processes")
        val self = ProcessHandle.current().pid().toInt()
        val mine = seen.busiest.firstOrNull { it.first.pid == self }
        assertTrue(mine != null && mine.second >= SPIN_NANOS / 2_000_000, "this JVM spun ${SPIN_NANOS / 1_000_000} ms: $seen")
    }

    private companion object {
        const val SPIN_NANOS = 600_000_000L
    }
}
