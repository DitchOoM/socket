package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.core.testing.coreTest
import com.ditchoom.socket.quic.core.testing.filesByName
import com.ditchoom.socket.quic.core.testing.scratchDirectory
import com.ditchoom.socket.quic.listQlogFiles
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogEvent
import com.ditchoom.socket.quic.trace.QlogPressure
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A core connection's qlog stays inside its [QlogBudget] on disk, and every byte the budget drops is reported. quiche
 * writes the qlog itself, so this runs a real connection on each platform and measures the directory with the platform's
 * own file API afterwards: what is on disk is the verdict, never the directory's own accounts.
 */
@OptIn(ExperimentalAtomicApi::class)
class CoreQlogBudgetTests {
    @Test
    fun aConnectionWritingPastItsBudgetKeepsItsHeadAndItsLatestAndReportsEveryDrop() =
        coreTest("CoreQlogBudgetTests.aConnectionWritingPastItsBudgetKeepsItsHeadAndItsLatestAndReportsEveryDrop") {
            val dir = scratchDirectory("core-qlog-budget")
            val reported = AtomicReference(emptyList<QlogEvent>())
            val budget =
                QlogBudget.of(SEGMENT_BYTES, connectionSegments = 3, directoryBytes = 1024L * SEGMENT_BYTES, directoryConnections = 8)
            val directory =
                QlogDirectory(dir, budget) { event ->
                    while (true) {
                        val current = reported.load()
                        if (reported.compareAndSet(current, current + event)) break
                    }
                }
            val capture =
                QuicTraceCapture(
                    captureFor = { QuicConnectionCapture(TraceSink { }, QlogTarget.Budgeted(directory, "conn-0001")) },
                    qlog = QlogDeclaration.Budgeted,
                )

            withCoreEchoServer { server ->
                val client = connectCore(server.port, options = coreTestOptions.copy(trace = capture))
                val stream = client.connection.openStream()
                repeat(ECHOES) { n -> echoOver(stream, "echo-$n".encodeToByteArray()) }
                client.connection.close(QuicError.NoError)
                withTimeout(10.seconds) { client.connection.coroutineContext.job.join() }
            }

            val onDisk = listQlogFiles(dir).filter { it.path.substringAfterLast('/').startsWith("conn-0001") }
            val sizes = onDisk.associate { it.path.substringAfterLast('/') to it.bytes }.entries.sortedBy { it.key }.associate { it.key to it.value }
            val events = reported.load()
            val dump = "on disk: $sizes\nevents:\n${events.joinToString("\n") { it.line }}"
            assertTrue(
                sizes.values.all { it <= 3 * SEGMENT_BYTES },
                "a qlog file outgrew its segment of $SEGMENT_BYTES bytes by more than twice: the record was not cut into segments.\n$dump",
            )
            assertTrue(sizes.size <= budget.connectionSegments, "more files than the connection keeps.\n$dump")
            assertEquals("conn-0001.sqlog", sizes.keys.first(), "the head, the handshake, is gone.\n$dump")
            assertTrue(PARAMETERS_SET in (filesByName(dir)["conn-0001.sqlog"] ?: ""), "the head does not hold the transport parameters.\n$dump")
            val truncated = events.filterIsInstance<QlogEvent.Truncated>()
            assertTrue(truncated.isNotEmpty(), "the connection wrote past its budget and no drop was reported.\n$dump")
            assertTrue(truncated.all { it.pressure == QlogPressure.ConnectionSegments }, dump)
            for (drop in truncated) {
                assertTrue(drop.dropped.path.substringAfterLast('/') !in sizes, "reported dropped but still on disk: ${drop.dropped}\n$dump")
            }
            val retained = directory.retained().single { it.name == "conn-0001" }.files
            assertEquals(sizes.values.sum(), retained.sumOf { it.bytes }, "the directory's accounts disagree with the disk.\n$dump")
        }

    private companion object {
        const val SEGMENT_BYTES = 32L * 1024L
        const val ECHOES = 600
        const val PARAMETERS_SET = "quic:parameters_set"
    }
}
