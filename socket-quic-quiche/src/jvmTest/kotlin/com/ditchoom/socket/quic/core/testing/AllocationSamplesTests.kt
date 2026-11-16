package com.ditchoom.socket.quic.core.testing

import jdk.jfr.Recording
import org.junit.Rule
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The allocation gates read their samples from a dump while another recording runs, as on a CI lane with a JFR ring:
 * CoreQuicAllocationTests failed on Apple FFM with "unknown field objectClass" reading the ring's other events.
 */
class AllocationSamplesTests {
    @get:Rule
    val flightEvidence = FlightEvidenceRule()

    @Test
    fun aDumpTakenWhileAnotherRecordingRunsYieldsOnlyAllocationSamples() {
        val ring = Recording().apply { enable("jdk.ThreadSleep").withThreshold(java.time.Duration.ZERO) }
        val samples = Recording().apply { enable(ALLOCATION_SAMPLE).with("throttle", "off") }
        ring.start()
        samples.start()
        val sink = ArrayList<ByteArray>()
        thread(name = THREAD) {
            repeat(ROUNDS) {
                sink += ByteArray(SIZE)
                Thread.sleep(1)
            }
        }.join()
        samples.stop()
        ring.stop()
        val dump = Files.createTempFile("alloc-samples", ".jfr")
        try {
            samples.dump(dump)
            val events = allocationSamples(dump) { it == THREAD }
            assertTrue(events.isNotEmpty(), "the thread's allocations were sampled")
            events.forEach { assertTrue(it.getClass("objectClass").name.isNotEmpty() && it.getLong("weight") > 0) }
        } finally {
            Files.delete(dump)
            ring.close()
            samples.close()
        }
    }

    private companion object {
        const val THREAD = "alloc-samples-under-test"
        const val ROUNDS = 50
        const val SIZE = 64 * 1024
    }
}
