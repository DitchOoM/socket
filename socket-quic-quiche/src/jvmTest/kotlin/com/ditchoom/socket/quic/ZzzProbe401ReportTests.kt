package com.ditchoom.socket.quic

import kotlin.test.Test

/**
 * DEBUG-ONLY (branch debug/401-soak-hunt): alphabetically last so it runs after every other class,
 * dumping the process-global Probe401 evidence into this class's system-out — the corruption can
 * strike ANY test in the task (round 10 hit StaleConnectionDiagnosticTests), and only the soak
 * suite's failure path prints the report itself.
 */
class ZzzProbe401ReportTests {
    @Test
    fun dumpProbe401Evidence() {
        println("PROBE401 concurrent same-conn entries (smoking gun if non-empty):")
        println(Probe401.overlapReport())
        println("PROBE401 conn thread census:")
        println(Probe401.threadReport())
    }
}
