package com.ditchoom.socket.quic

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.flow.ReadResult
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogClosed
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogEvent
import com.ditchoom.socket.quic.trace.QlogPressure
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A connection's qlog stays inside its [QlogBudget] on disk, and every byte the budget drops is
 * reported (#624, #627).
 *
 * quiche writes the qlog itself, so these run a real quiche pair through the migration sim on virtual
 * time and measure the directory with the platform's own file API afterwards: what is on disk is the
 * verdict, never the directory's own accounts.
 */
abstract class QlogBudgetTestSuite {
    internal abstract fun simEnv(): MigrationSimEnv

    protected open suspend fun wrapTestBody(block: suspend () -> Unit): Unit = block()

    /** A new, empty directory for one test. */
    protected abstract fun freshDirectory(tag: String): String

    /** Every regular file in [dir] with its size in bytes, read by the platform's own file API. */
    protected abstract fun filesIn(dir: String): Map<String, Long>

    protected abstract fun readText(path: String): String

    private fun sorted(files: Map<String, Long>): Map<String, Long> = files.entries.sortedBy { it.key }.associate { it.key to it.value }

    private fun captureInto(
        directory: QlogDirectory,
        name: String,
    ): QuicTraceCapture = QuicTraceCapture(captureFor = { QuicConnectionCapture(TraceSink { }, QlogTarget.Budgeted(directory, name)) })

    /** A connection that echoes [echoes] times; the server echoes on the stream the client opens. */
    private suspend fun echoingConnection(
        seed: Long,
        capture: QuicTraceCapture,
        echoes: Int,
    ) {
        withMigrationSim(simEnv(), seed = seed, quicOptions = migrationSimOptions(trace = capture)) {
            val serverJob =
                client.launch {
                    val st = server.acceptStream()
                    while (true) {
                        val d = st.read(60.seconds)
                        if (d !is ReadResult.Data) break
                        st.write(d.buffer, 30.seconds)
                        d.buffer.freeIfNeeded()
                    }
                }
            try {
                val stream = client.openStream()
                repeat(echoes) { n ->
                    val payload = "echo-$n"
                    val out = BufferFactory.network().allocate(payload.length)
                    out.writeString(payload, Charset.UTF8)
                    out.resetForRead()
                    stream.write(out, 30.seconds)
                    out.freeNativeMemory()
                    val r = stream.read(60.seconds)
                    if (r is ReadResult.Data) r.buffer.freeIfNeeded()
                }
            } finally {
                serverJob.cancel()
            }
        }
    }

    @Test
    fun aConnectionWritingPastItsBudgetKeepsItsHeadAndItsLatestAndReportsEveryDrop() =
        runTest {
            wrapTestBody {
                val dir = freshDirectory("qlog-budget-connection")
                val events = mutableListOf<QlogEvent>()
                val budget =
                    QlogBudget.of(
                        SEGMENT_BYTES,
                        connectionSegments = 3,
                        directoryBytes = 1024L * SEGMENT_BYTES,
                        directoryConnections = 8,
                    )
                val directory = QlogDirectory(dir, budget) { events += it }

                echoingConnection(seed = 624_001L, capture = captureInto(directory, "conn-0001"), echoes = 400)

                val onDisk = sorted(filesIn(dir).filterKeys { it.startsWith("conn-0001") })
                val dump = "on disk: $onDisk\nevents:\n${events.joinToString("\n") { it.line }}"
                assertTrue(
                    onDisk.values.all { it <= 3 * SEGMENT_BYTES },
                    "a qlog file outgrew its segment of $SEGMENT_BYTES bytes by more than twice: the connection's " +
                        "record was not cut into segments at all.\n$dump",
                )
                assertTrue(onDisk.size <= budget.connectionSegments, "more files than the connection keeps.\n$dump")
                assertEquals("conn-0001.sqlog", onDisk.keys.first(), "the head — the handshake — is gone.\n$dump")
                assertTrue(PARAMETERS_SET in readText("$dir/conn-0001.sqlog"), "the head does not hold the transport parameters.\n$dump")
                val truncated = events.filterIsInstance<QlogEvent.Truncated>()
                assertTrue(truncated.isNotEmpty(), "the connection wrote past its budget and no drop was reported.\n$dump")
                assertTrue(truncated.all { it.pressure == QlogPressure.ConnectionSegments }, dump)
                for (drop in truncated) {
                    assertTrue(
                        drop.dropped.path.substringAfterLast('/') !in onDisk,
                        "reported dropped but still on disk: ${drop.dropped}\n$dump",
                    )
                }
                val retained = directory.retained().single { it.name == "conn-0001" }.files
                assertEquals(
                    onDisk.values.sum(),
                    retained.sumOf { it.bytes },
                    "the directory's accounts disagree with the disk, so its budget is not the disk's.\n$dump",
                )
            }
        }

    @Test
    fun aReconnectStormKeepsItsConnectionLimitAndTheConnectionThatCarriedTraffic() =
        runTest {
            wrapTestBody {
                val dir = freshDirectory("qlog-budget-storm")
                val events = mutableListOf<QlogEvent>()
                val budget =
                    QlogBudget.of(
                        SEGMENT_BYTES,
                        connectionSegments = 3,
                        directoryBytes = 1024L * SEGMENT_BYTES,
                        directoryConnections = 4,
                    )
                val directory = QlogDirectory(dir, budget) { events += it }

                echoingConnection(seed = 627_001L, capture = captureInto(directory, "conn-0001"), echoes = 5)
                // A storm: attempts whose every datagram vanishes, each giving up on the handshake.
                val attempts = (2..9).map { "conn-" + it.toString().padStart(4, '0') }
                for ((n, name) in attempts.withIndex()) {
                    try {
                        withMigrationSim(
                            simEnv(),
                            seed = 627_100L + n,
                            primaryImpairment = PathImpairment(reach = LinkReach(PathReach.Dark)),
                            quicOptions = migrationSimOptions(trace = captureInto(directory, name)),
                            establishTimeout = 2.seconds,
                        ) { }
                    } catch (_: TimeoutCancellationException) {
                        // The attempt never established — the storm this test is about.
                    }
                }

                val onDisk = sorted(filesIn(dir).filterKeys { it.endsWith(".sqlog") })
                val records = onDisk.keys.map { it.substringBefore('_').removeSuffix(".sqlog") }.distinct()
                val dump = "on disk: $onDisk\nevents:\n${events.joinToString("\n") { it.line }}"
                assertTrue(
                    records.size <= budget.directoryConnections,
                    "${records.size} connection records on disk after a storm of ${attempts.size} attempts, against a " +
                        "limit of ${budget.directoryConnections}: nothing bounds the file count.\n$dump",
                )
                assertEquals(
                    listOf("conn-0001", "conn-0007", "conn-0008", "conn-0009"),
                    records,
                    "the connection that carried traffic must outlive the storm's failed attempts.\n$dump",
                )
                val evicted = events.filterIsInstance<QlogEvent.Evicted>()
                assertEquals(attempts.take(5), evicted.map { it.connection }, "every eviction is reported, oldest first.\n$dump")
                assertTrue(
                    evicted.all { it.closed == QlogClosed.NeverEstablished && it.pressure == QlogPressure.DirectoryConnections },
                    dump,
                )
            }
        }

    private companion object {
        const val SEGMENT_BYTES = 32L * 1024L
        const val PARAMETERS_SET = "quic:parameters_set"
    }
}
