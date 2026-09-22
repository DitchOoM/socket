package com.ditchoom.socket.quic.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The retention decisions a [QlogDirectory] makes, driven directly: no quiche, no files, no clock.
 * Each test states what an analysis needed from a walk's qlog and checks the directory keeps it.
 */
class QlogDirectoryTests {
    private val events = mutableListOf<QlogEvent>()

    private fun directory(
        segmentBytes: Long = 10_000,
        connectionSegments: Int = 3,
        directoryBytes: Long = 1_000_000,
        directoryConnections: Int = 100,
    ) = QlogDirectory("/qlog", QlogBudget.of(segmentBytes, connectionSegments, directoryBytes, directoryConnections)) { events += it }

    private fun QlogDirectory.admit(name: String): QlogRecord =
        assertIs<QlogOpening.Admitted>(open(name), "$name was refused: $events").record

    /** Write [bytes] into [record]'s current segment and rotate whenever the directory says one is due. */
    private fun QlogRecord.write(
        bytes: Long,
        perStep: Long = 1_000,
    ): List<QlogFile> {
        val dropped = mutableListOf<QlogFile>()
        var inSegment = 0L
        var written = 0L
        while (written < bytes) {
            inSegment += perStep
            written += perStep
            when (val step = observe(inSegment)) {
                is QlogStep.Keep -> dropped += step.evicted
                is QlogStep.RotateDue -> {
                    dropped += step.evicted
                    dropped += rotated(step.next, inSegment)
                    inSegment = 0
                }
            }
        }
        return dropped
    }

    private fun QlogDirectory.names(): List<String> = retained().map { it.name }

    private fun QlogDirectory.files(name: String): List<String> =
        retained().single { it.name == name }.files.map { it.path.substringAfterLast('/') }

    @Test
    fun aConnectionPastItsSegmentsKeepsItsHeadAndItsLatest() {
        val dir = directory(segmentBytes = 10_000, connectionSegments = 3)
        val conn = dir.admit("conn-0001")
        conn.established()

        val dropped = conn.write(55_000)

        assertEquals(
            listOf("conn-0001.sqlog", "conn-0001_seg0005.sqlog", "conn-0001_seg0006.sqlog"),
            dir.files("conn-0001"),
            "the head holds the handshake and the latest holds whatever happened last: $events",
        )
        assertEquals(
            listOf("conn-0001_seg0002.sqlog", "conn-0001_seg0003.sqlog", "conn-0001_seg0004.sqlog"),
            dropped.map { it.path.substringAfterLast('/') },
            "every dropped middle segment is handed back for deletion, oldest first",
        )
        val truncated = events.filterIsInstance<QlogEvent.Truncated>()
        assertEquals(dropped, truncated.map { it.dropped }, "every drop is reported: $events")
        assertTrue(truncated.all { it.pressure == QlogPressure.ConnectionSegments }, "the connection's own limit dropped them: $truncated")
    }

    @Test
    fun aReconnectStormEvictsItsFailedAttemptsBeforeTheConnectionThatCarriedTraffic() {
        val dir = directory(directoryConnections = 4)
        val walked = dir.admit("conn-0001")
        walked.established()
        walked.write(25_000)
        walked.close(5_000)

        val dropped = mutableListOf<QlogFile>()
        for (n in 2..11) {
            val name = "conn-" + n.toString().padStart(4, '0')
            val opening = assertIs<QlogOpening.Admitted>(dir.open(name), "attempt $n was refused: $events")
            dropped += opening.evicted
            dropped += opening.record.close(2_000)
        }

        assertEquals(listOf("conn-0001", "conn-0009", "conn-0010", "conn-0011"), dir.names(), "retained after the storm: $events")
        val evicted = events.filterIsInstance<QlogEvent.Evicted>()
        assertEquals((2..8).map { "conn-" + it.toString().padStart(4, '0') }, evicted.map { it.connection }, "oldest failed attempt first")
        assertTrue(
            evicted.all { it.closed == QlogClosed.NeverEstablished && it.pressure == QlogPressure.DirectoryConnections },
            "only failed attempts gave way, and only to the connection limit: $evicted",
        )
        assertEquals(evicted.flatMap { it.files }, dropped, "every evicted file is handed back for deletion")
    }

    @Test
    fun aFullDirectoryDropsMiddlesBeforeAnyConnectionAndNeverAHeadOrALatest() {
        val dir = directory(segmentBytes = 10_000, connectionSegments = 100, directoryBytes = 60_000)
        val first = dir.admit("conn-0001")
        first.established()
        first.write(40_000)
        first.close(10_000)
        val second = dir.admit("conn-0002")
        second.established()

        second.write(30_000)

        val truncated = events.filterIsInstance<QlogEvent.Truncated>()
        assertTrue(truncated.isNotEmpty(), "the directory went over its bytes and dropped nothing: ${dir.retained()}")
        assertTrue(truncated.all { it.pressure == QlogPressure.DirectoryBytes }, "the directory's limit dropped them: $truncated")
        assertEquals(emptyList(), events.filterIsInstance<QlogEvent.Evicted>(), "a whole connection went while middles remained")
        assertEquals("conn-0001.sqlog", dir.files("conn-0001").first(), "the first connection's head")
        assertEquals("conn-0001_seg0005.sqlog", dir.files("conn-0001").last(), "the first connection's last segment, where it ended")
        assertTrue(dir.retained().sumOf { r -> r.files.sumOf { it.bytes } } <= 60_000, "over budget: ${dir.retained()}")
    }

    @Test
    fun whenEveryRecordIsLiveTheNextConnectionIsRefusedAndSaysWhy() {
        val dir = directory(directoryConnections = 2)
        dir.admit("conn-0001")
        dir.admit("conn-0002")

        val third = dir.open("conn-0003")

        assertEquals(QlogOpening.Refused(QlogRefusal.DirectoryConnections(live = 2, limit = 2)), third)
        assertEquals(
            "QLOG-REFUSED conn=conn-0003 reason=DirectoryConnections(live=2 limit=2) — this connection has no qlog",
            events.last().line,
        )
        assertEquals(listOf("conn-0001", "conn-0002"), dir.names(), "a refusal evicts nothing")
    }

    @Test
    fun aRotationQuicheRefusesStopsRotatingAndSaysSo() {
        val dir = directory(segmentBytes = 10_000)
        val conn = dir.admit("conn-0001")

        val due = assertIs<QlogStep.RotateDue>(conn.observe(10_000))
        conn.rotationRefused(due.next)

        assertIs<QlogStep.Keep>(conn.observe(50_000), "a record quiche will not rotate must stop asking")
        assertEquals(listOf("conn-0001.sqlog"), dir.files("conn-0001"))
        assertEquals(QlogEvent.RotationRefused("conn-0001", "/qlog/conn-0001_seg0002.sqlog"), events.last())
    }

    @Test
    fun anEarlierProcessesQlogIsCountedAndGoesFirst() {
        val dir = directory(segmentBytes = 10_000, connectionSegments = 100, directoryBytes = 50_000)
        dir.adopt(
            listOf(
                QlogFile("/qlog/quiche-server-aa.sqlog", 10_000),
                QlogFile("/qlog/quiche-server-aa_seg0002.sqlog", 10_000),
                QlogFile("/qlog/quiche-server-aa_seg0003.sqlog", 10_000),
                QlogFile("/qlog/qlog-budget.log", 99_999),
            ),
        )
        assertEquals(QlogEvent.Inherited(connections = 1, files = 3, bytes = 30_000), events.single())

        val conn = dir.admit("quiche-server-bb")
        conn.established()
        conn.write(25_000)

        val truncated = events.filterIsInstance<QlogEvent.Truncated>()
        assertEquals(
            listOf("quiche-server-aa"),
            truncated.map { it.connection }.distinct(),
            "the inherited record's middle goes first: $events",
        )
        assertEquals(listOf("quiche-server-aa", "quiche-server-bb"), dir.names())
    }

    @Test
    fun theRealWalkFitsInsideItsOwnBudget() {
        val budget = QlogBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 1)

        assertEquals(3452L * 1024 * 1024, budget.directoryBytes)
        assertEquals(24_134_400L, budget.segmentBytes, "one hour at the measured rate")
        assertEquals(150, budget.connectionSegments)
        assertEquals(4500, budget.directoryConnections)
        // The longest walk connection recorded: 534.87 MB in 1,343.7 min on one Samsung connection.
        val measuredBytesPerMinute = 534_870_000.0 / 1343.7
        assertTrue(
            75 * 60 * measuredBytesPerMinute <= budget.segmentBytes * budget.connectionSegments,
            "one connection spanning the whole walk at the measured rate must fit its own share",
        )
    }

    @Test
    fun theWalkServersBudgetHoldsEveryLanesWholeWalk() {
        // Two walking devices, one lane per address family each.
        val budget = QlogBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 4)

        assertEquals(4 * 3452L * 1024 * 1024, budget.directoryBytes)
        assertEquals(150, budget.connectionSegments, "a connection may fill its own lane's share, not another's")
        assertEquals(18000, budget.directoryConnections)
        assertEquals(
            "QLOG-BUDGET mb=13808 segmentKb=23568 connectionSegments=150 connections=18000 " +
                "walkMinutes=4500 echoIntervalMs=250 lanes=4 plannedExchanges=4320000",
            budget.line,
        )
    }

    @Test
    fun aBudgetFittedToASmallDiskSaysSo() {
        val budget = QlogBudget.forWalk(minutes = 4500, echoInterval = 250.milliseconds, lanes = 1).fittedTo(1024L * 1024 * 1024)

        assertEquals(1024L * 1024 * 1024, budget.directoryBytes)
        assertTrue(budget.line.endsWith("fittedToAvailableMb=1024"), budget.line)
    }

    @Test
    fun anInconsistentBudgetIsRaisedToOneThatHoldsAHeadAndACurrentSegment() {
        val budget = QlogBudget.of(segmentBytes = 1, connectionSegments = 0, directoryBytes = 0, directoryConnections = 0)

        assertEquals(QlogBudget.MIN_SEGMENT_BYTES, budget.segmentBytes)
        assertEquals(2, budget.connectionSegments)
        assertEquals(2 * QlogBudget.MIN_SEGMENT_BYTES, budget.directoryBytes)
        assertEquals(1, budget.directoryConnections)
    }
}
