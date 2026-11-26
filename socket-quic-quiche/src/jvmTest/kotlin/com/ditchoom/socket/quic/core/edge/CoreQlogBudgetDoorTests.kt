package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.demux.withCoreEchoServer
import com.ditchoom.socket.quic.skipOnMissingNativeLib
import com.ditchoom.socket.quic.trace.QlogBudget
import com.ditchoom.socket.quic.trace.QlogClosed
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogDirectory
import com.ditchoom.socket.quic.trace.QlogEvent
import com.ditchoom.socket.quic.trace.QlogLifecycle
import com.ditchoom.socket.quic.trace.QlogPressure
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.QuicConnectionCapture
import com.ditchoom.socket.quic.trace.QuicTraceCapture
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A reconnect storm against a silent peer keeps the directory's connection limit and the connection that carried traffic,
 * and the `QUIC_QLOG_DIR` door keeps its budget and its notes. The segment budget of one connection runs on every platform
 * in [CoreQlogBudgetTests].
 */
class CoreQlogBudgetDoorTests {
    private fun freshDirectory(tag: String): File = Files.createTempDirectory(tag).toFile()

    private fun sorted(files: Map<String, Long>): Map<String, Long> = files.entries.sortedBy { it.key }.associate { it.key to it.value }

    private fun filesIn(dir: File): Map<String, Long> =
        dir
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .associate { it.name to it.length() }

    private fun captureInto(
        directory: QlogDirectory,
        name: String,
    ): QuicTraceCapture =
        QuicTraceCapture(
            captureFor = { QuicConnectionCapture(TraceSink { }, QlogTarget.Budgeted(directory, name)) },
            qlog = QlogDeclaration.Budgeted,
        )

    /** One client connection that echoes [echoes] times to a core server, closed and freed when this returns. */
    private suspend fun echoingConnection(
        capture: QuicTraceCapture,
        echoes: Int,
    ) {
        withCoreEchoServer { server ->
            val client = connectCore(server.port, options = coreTestOptions.copy(trace = capture))
            val stream = client.connection.openStream()
            repeat(echoes) { n -> echoOver(stream, "echo-$n".encodeToByteArray()) }
            client.connection.close(QuicError.NoError)
            withTimeout(10.seconds) { client.connection.coroutineContext.job.join() }
        }
    }

    @Test
    fun aReconnectStormKeepsItsConnectionLimitAndTheConnectionThatCarriedTraffic() =
        runBlocking {
            skipOnMissingNativeLib(CoreQlogBudgetDoorTests::class) {
                val dir = freshDirectory("core-qlog-budget-storm")
                val events = ConcurrentLinkedQueue<QlogEvent>()
                val budget =
                    QlogBudget.of(
                        SEGMENT_BYTES,
                        connectionSegments = 3,
                        directoryBytes = 1024L * SEGMENT_BYTES,
                        directoryConnections = 4,
                    )
                val directory = QlogDirectory(dir.absolutePath, budget) { events += it }

                echoingConnection(captureInto(directory, "conn-0001"), echoes = 5)
                // A storm: attempts to a peer that never answers, each giving up on the handshake.
                val attempts = (2..9).map { "conn-" + it.toString().padStart(4, '0') }
                DatagramChannel.open().use { silent ->
                    silent.bind(InetSocketAddress("127.0.0.1", 0))
                    val port = (silent.localAddress as InetSocketAddress).port
                    for (name in attempts) {
                        try {
                            connectCore(port, options = coreTestOptions.copy(trace = captureInto(directory, name)), timeout = 1.seconds)
                        } catch (_: TimeoutCancellationException) {
                            // The attempt never established: the storm this test is about.
                        }
                    }
                }
                awaitNoLiveRecord(directory)

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
                assertTrue(evicted.all { it.closed == QlogClosed.NeverEstablished && it.pressure == QlogPressure.DirectoryConnections }, dump)
            }
        }

    /** A record is closed when its connection is freed, which a failed connect reaches a moment after it returns. */
    private suspend fun awaitNoLiveRecord(directory: QlogDirectory) {
        withTimeout(10.seconds) {
            while (directory.retained().any { it.lifecycle is QlogLifecycle.Live }) delay(20)
        }
    }

    @Test
    fun theEnvironmentDoorKeepsItsBudgetAndItsLossesInTheDirectoryItWrites() =
        runBlocking {
            skipOnMissingNativeLib(CoreQlogBudgetDoorTests::class) {
                val dir = freshDirectory("core-qlog-budget-env")
                // An earlier server process's record, which this one must count rather than ignore.
                File(dir, "quiche-server-0123abcd.sqlog").writeText("x".repeat(1000))
                val previous = System.getProperty(QLOG_DIR_PROPERTY)
                System.setProperty(QLOG_DIR_PROPERTY, dir.absolutePath)
                try {
                    repeat(2) {
                        withCoreEchoServer { server ->
                            val client = connectCore(server.port)
                            client.connection.close(QuicError.NoError)
                            withTimeout(10.seconds) { client.connection.coroutineContext.job.join() }
                        }
                    }
                } finally {
                    if (previous == null) System.clearProperty(QLOG_DIR_PROPERTY) else System.setProperty(QLOG_DIR_PROPERTY, previous)
                }

                val notes = File(dir, "qlog-budget.log").readText()
                assertTrue(
                    "QLOG-BUDGET mb=13808 segmentKb=23568 connectionSegments=150 connections=18000 walkMinutes=4500 " +
                        "echoIntervalMs=250 lanes=4" in notes,
                    "the directory must say which budget it keeps, in the directory a pull collects:\n$notes",
                )
                assertTrue("QLOG-INHERITED connections=1 files=1 bytes=1000" in notes, "the earlier record is counted:\n$notes")
                val clientQlogs = dir.listFiles { f -> f.name.endsWith(".sqlog") }.orEmpty().filter { CLIENT_VANTAGE in it.readText() }
                assertEquals(2, clientQlogs.size, "one client qlog per connection: ${clientQlogs.map { it.name }}")
            }
        }

    private companion object {
        const val SEGMENT_BYTES = 32L * 1024L
        const val PARAMETERS_SET = "quic:parameters_set"
        const val QLOG_DIR_PROPERTY = "quic.qlog.dir"
        const val CLIENT_VANTAGE = "\"vantage_point\":{\"type\":\"client\"}"
    }
}
