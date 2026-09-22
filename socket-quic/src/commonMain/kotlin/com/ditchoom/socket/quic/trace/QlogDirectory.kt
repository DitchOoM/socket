@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.trace

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One directory of qlog records held to a [QlogBudget]: which connections have a record, which
 * segments of each are kept, and what gives way when a limit is reached.
 *
 * The directory keeps accounts and makes decisions; it does no I/O. Whoever drives a record measures
 * its current segment on disk, hands quiche the next segment when one is due, and deletes the files
 * each call returns — which is why every decision names the files it dropped.
 *
 * When the directory is over its bytes, what goes first is what an analysis needs least:
 *  1. closed connections that never completed a handshake, oldest first — a reconnect storm's
 *     attempts, each already a line in the log that made them;
 *  2. the oldest segment that is neither a connection's head nor its latest — the steady state
 *     between a handshake and whatever happened last;
 *  3. closed connections that carried traffic, oldest first, whole.
 * A connection past its own [QlogBudget.connectionSegments] drops its oldest middle segment, and a
 * directory at [QlogBudget.directoryConnections] evicts closed connections in the order of 1 and 3.
 * When only live connections remain and there is still no room, a new connection is refused. Every
 * one of these is reported to [report] as a [QlogEvent], once, by the caller whose change made it.
 *
 * Lock-free: the accounts are an immutable snapshot replaced by compare-and-set, so any number of
 * connections may share one directory.
 */
class QlogDirectory(
    val path: String,
    val budget: QlogBudget,
    private val report: (QlogEvent) -> Unit,
) {
    private val ledger = AtomicReference(Ledger(emptyList(), 0L))
    private val root = path.trimEnd('/')

    /** Where segment [index] of [name]'s record is written: `<name>.sqlog`, then `<name>_seg0002.sqlog` and on. */
    fun segmentPath(
        name: String,
        index: Int,
    ): String = if (index == 1) "$root/$name.sqlog" else "$root/${name}_seg${index.toString().padStart(4, '0')}.sqlog"

    /**
     * Count [files] — qlog an earlier process left in this directory, oldest first — against the
     * budget, as closed records of their connections. A file whose name is not a segment of this
     * directory's naming is not a qlog record and is left out.
     */
    fun adopt(files: List<QlogFile>) {
        update { draft -> draft.adopt(files) }
    }

    /** Start [name]'s record, or refuse it; see the class KDoc for what an admission may evict. */
    fun open(name: String): QlogOpening = update { draft -> draft.open(name) }

    /** [file] was dropped from the accounts and could not be deleted: the disk holds more than they say. */
    fun deleteFailed(file: QlogFile) {
        update { draft -> draft.events += QlogEvent.DeleteFailed(file) }
    }

    /** The connections that currently have a record, oldest first, with the files each keeps. */
    fun retained(): List<QlogRetained> =
        ledger.load().entries.map { entry ->
            QlogRetained(entry.name, entry.lifecycle, entry.parts.map { QlogFile(it.segment.path, it.bytes) })
        }

    internal fun established(name: String) {
        update { draft -> draft.established(name) }
    }

    internal fun observe(
        name: String,
        bytesOnDisk: Long,
    ): QlogStep = update { draft -> draft.observe(name, bytesOnDisk) }

    internal fun rotated(
        name: String,
        next: QlogSegment,
        previousBytes: Long,
    ): List<QlogFile> = update { draft -> draft.rotated(name, next, previousBytes) }

    internal fun rotationRefused(
        name: String,
        next: QlogSegment,
    ) {
        update { draft -> draft.rotationRefused(name, next) }
    }

    internal fun refusedByQuiche(name: String) {
        update { draft -> draft.refusedByQuiche(name) }
    }

    internal fun close(
        name: String,
        bytesOnDisk: Long,
    ): List<QlogFile> = update { draft -> draft.close(name, bytesOnDisk) }

    /**
     * One change to the accounts: [block] runs against a private working copy of the current
     * snapshot, and its events are reported only by the caller whose copy was installed — a copy
     * that lost the race is thrown away with its events and rebuilt from the winner's snapshot.
     */
    private inline fun <T> update(block: (Draft) -> T): T {
        while (true) {
            val before = ledger.load()
            val draft = Draft(before)
            val result = block(draft)
            if (ledger.compareAndSet(before, draft.freeze())) {
                // The directory is the instrument, not the connection: a reporter that throws cannot
                // be allowed to take the driver loop down with it.
                draft.events.forEach { event -> runCatching { report(event) } }
                return result
            }
        }
    }

    private class Ledger(
        val entries: List<Entry>,
        val nextOrder: Long,
    )

    private class Entry(
        val name: String,
        val lifecycle: QlogLifecycle,
        val rotation: Rotation,
        val parts: List<Part>,
    ) {
        val bytes: Long get() = parts.sumOf { it.bytes }

        /** Every segment but the head and the latest. */
        val middles: List<Part> get() = if (parts.size <= 2) emptyList() else parts.subList(1, parts.size - 1)

        /** The head and the latest segment: what this record keeps however hard the directory is pressed. */
        val floorBytes: Long get() = if (parts.size == 1) parts.single().bytes else parts.first().bytes + parts.last().bytes

        fun with(
            lifecycle: QlogLifecycle = this.lifecycle,
            rotation: Rotation = this.rotation,
            parts: List<Part> = this.parts,
        ): Entry = Entry(name, lifecycle, rotation, parts)
    }

    /** One segment and the bytes counted for it; [order] is its place in the directory's creation order. */
    private class Part(
        val segment: QlogSegment,
        val bytes: Long,
        val order: Long,
    ) {
        fun measured(bytes: Long): Part = Part(segment, bytes, order)
    }

    /** Whether quiche can still be handed a next segment for this record. */
    private enum class Rotation { Rotating, Stopped }

    private sealed interface ParsedName {
        data object NotARecord : ParsedName

        data class Segment(
            val name: String,
            val segment: QlogSegment,
        ) : ParsedName
    }

    private inner class Draft(
        from: Ledger,
    ) {
        val entries: MutableList<Entry> = from.entries.toMutableList()
        var nextOrder: Long = from.nextOrder
        val events: MutableList<QlogEvent> = mutableListOf()
        private val evicted: MutableList<QlogFile> = mutableListOf()

        fun freeze(): Ledger = Ledger(entries.toList(), nextOrder)

        private val bytes: Long get() = entries.sumOf { it.bytes }

        private fun indexOf(name: String): Int = entries.indexOfFirst { it.name == name }

        fun adopt(files: List<QlogFile>) {
            // Older than anything this process opened, so first in line wherever age decides: ahead
            // in the record order, and below every existing segment in the creation order.
            var order = (entries.flatMap { it.parts }.minOfOrNull { it.order } ?: nextOrder) - files.size
            val byName = LinkedHashMap<String, MutableList<Part>>()
            for (file in files) {
                when (val parsed = parse(file.path)) {
                    ParsedName.NotARecord -> Unit
                    is ParsedName.Segment -> byName.getOrPut(parsed.name) { mutableListOf() } += Part(parsed.segment, file.bytes, order++)
                }
            }
            val inherited =
                byName
                    .filterKeys { indexOf(it) < 0 }
                    .map { (name, parts) ->
                        Entry(name, QlogLifecycle.Closed(QlogClosed.Inherited), Rotation.Stopped, parts.sortedBy { it.segment.index })
                    }
            if (inherited.isEmpty()) return
            entries.addAll(0, inherited)
            events += QlogEvent.Inherited(inherited.size, inherited.sumOf { it.parts.size }, inherited.sumOf { it.bytes })
        }

        /** `<name>.sqlog` is segment 1 of `name`, `<name>_segNNNN.sqlog` segment NNNN; any other file is not a record. */
        private fun parse(filePath: String): ParsedName {
            val fileName = filePath.substringAfterLast('/')
            if (!fileName.endsWith(SUFFIX)) return ParsedName.NotARecord
            val stem = fileName.removeSuffix(SUFFIX)
            val marker = stem.lastIndexOf(SEGMENT_MARKER)
            val index = if (marker < 0) 1 else stem.substring(marker + SEGMENT_MARKER.length).toIntOrNull() ?: 1
            val name = if (index == 1) stem else stem.substring(0, marker)
            return ParsedName.Segment(name, QlogSegment(index, segmentPath(name, index)))
        }

        fun open(name: String): QlogOpening {
            if (indexOf(name) >= 0) return refuse(name, QlogRefusal.NameInUse)
            val closed = entries.count { it.lifecycle is QlogLifecycle.Closed }
            val live = entries.size - closed
            if (live + 1 > budget.directoryConnections) {
                return refuse(name, QlogRefusal.DirectoryConnections(live, budget.directoryConnections))
            }
            // What no eviction can reclaim: each live record's head and current segment.
            val liveFloor = entries.filter { it.lifecycle is QlogLifecycle.Live }.sumOf { it.floorBytes }
            if (liveFloor > budget.directoryBytes) {
                return refuse(name, QlogRefusal.DirectoryBytes(liveFloor, budget.directoryBytes))
            }
            while (entries.size + 1 > budget.directoryConnections) {
                if (!evictClosed(QlogPressure.DirectoryConnections)) break
            }
            relieveBytes()
            val head = QlogSegment(1, segmentPath(name, 1))
            entries += Entry(name, QlogLifecycle.Live.Handshaking, Rotation.Rotating, listOf(Part(head, 0L, nextOrder++)))
            return QlogOpening.Admitted(QlogRecord(this@QlogDirectory, name), head, evicted.toList())
        }

        private fun refuse(
            name: String,
            reason: QlogRefusal,
        ): QlogOpening {
            events += QlogEvent.Refused(name, reason)
            return QlogOpening.Refused(reason)
        }

        fun established(name: String) {
            val i = indexOf(name)
            if (i < 0) return
            val entry = entries[i]
            if (entry.lifecycle == QlogLifecycle.Live.Handshaking) entries[i] = entry.with(lifecycle = QlogLifecycle.Live.Established)
        }

        fun observe(
            name: String,
            bytesOnDisk: Long,
        ): QlogStep {
            val i = indexOf(name)
            if (i < 0) return QlogStep.Keep(emptyList())
            val entry = measureCurrent(i, bytesOnDisk)
            relieveBytes()
            val current = entry.parts.last().segment
            return if (entry.rotation == Rotation.Rotating && bytesOnDisk >= budget.segmentBytes) {
                QlogStep.RotateDue(QlogSegment(current.index + 1, segmentPath(name, current.index + 1)), evicted.toList())
            } else {
                QlogStep.Keep(evicted.toList())
            }
        }

        fun rotated(
            name: String,
            next: QlogSegment,
            previousBytes: Long,
        ): List<QlogFile> {
            val i = indexOf(name)
            if (i < 0) return emptyList()
            entries[i] = measureCurrent(i, previousBytes).let { it.with(parts = it.parts + Part(next, 0L, nextOrder++)) }
            events += QlogEvent.Segmented(name, next)
            while (entries[i].parts.size > budget.connectionSegments) {
                dropMiddle(i, entries[i].parts[1], QlogPressure.ConnectionSegments)
            }
            relieveBytes()
            return evicted.toList()
        }

        fun rotationRefused(
            name: String,
            next: QlogSegment,
        ) {
            val i = indexOf(name)
            if (i < 0) return
            entries[i] = entries[i].with(rotation = Rotation.Stopped)
            events += QlogEvent.RotationRefused(name, next.path)
        }

        fun refusedByQuiche(name: String) {
            val i = indexOf(name)
            if (i < 0) return
            val head =
                entries
                    .removeAt(i)
                    .parts
                    .first()
                    .segment
            events += QlogEvent.QuicheRefused(name, head.path)
        }

        fun close(
            name: String,
            bytesOnDisk: Long,
        ): List<QlogFile> {
            val i = indexOf(name)
            if (i < 0) return emptyList()
            val entry = measureCurrent(i, bytesOnDisk)
            val how = if (entry.lifecycle == QlogLifecycle.Live.Established) QlogClosed.Established else QlogClosed.NeverEstablished
            entries[i] = entry.with(lifecycle = QlogLifecycle.Closed(how), rotation = Rotation.Stopped)
            relieveBytes()
            return evicted.toList()
        }

        private fun measureCurrent(
            i: Int,
            bytesOnDisk: Long,
        ): Entry {
            val entry = entries[i]
            val parts = entry.parts.dropLast(1) + entry.parts.last().measured(bytesOnDisk)
            return entry.with(parts = parts).also { entries[i] = it }
        }

        /** Evict until the directory is within its bytes, most expendable first; stops when only what must stay is left. */
        private fun relieveBytes() {
            while (bytes > budget.directoryBytes) {
                val gave =
                    evictClosed(QlogPressure.DirectoryBytes, QlogClosed.NeverEstablished) ||
                        dropOldestMiddle() ||
                        evictClosed(QlogPressure.DirectoryBytes)
                if (!gave) return
            }
        }

        /** Evict the oldest closed record: one that never established first, then any. */
        private fun evictClosed(pressure: QlogPressure): Boolean =
            evictClosed(pressure, QlogClosed.NeverEstablished) || evictClosed(pressure, QlogClosed.Established, QlogClosed.Inherited)

        private fun evictClosed(
            pressure: QlogPressure,
            vararg kinds: QlogClosed,
        ): Boolean {
            for ((i, entry) in entries.withIndex()) {
                val lifecycle = entry.lifecycle
                if (lifecycle !is QlogLifecycle.Closed || lifecycle.how !in kinds) continue
                entries.removeAt(i)
                val files = entry.parts.map { QlogFile(it.segment.path, it.bytes) }
                evicted += files
                events += QlogEvent.Evicted(entry.name, files, lifecycle.how, pressure)
                return true
            }
            return false
        }

        /** Drop the oldest segment, across every record, that is neither its record's head nor its latest. */
        private fun dropOldestMiddle(): Boolean {
            val middles = entries.withIndex().flatMap { (i, entry) -> entry.middles.map { i to it } }
            if (middles.isEmpty()) return false
            val (i, part) = middles.minBy { it.second.order }
            dropMiddle(i, part, QlogPressure.DirectoryBytes)
            return true
        }

        private fun dropMiddle(
            i: Int,
            part: Part,
            pressure: QlogPressure,
        ) {
            val entry = entries[i]
            entries[i] = entry.with(parts = entry.parts - part)
            val file = QlogFile(part.segment.path, part.bytes)
            evicted += file
            events += QlogEvent.Truncated(entry.name, file, pressure)
        }
    }

    private companion object {
        const val SUFFIX = ".sqlog"
        const val SEGMENT_MARKER = "_seg"
    }
}

/**
 * One connection's qlog record in a [QlogDirectory], driven by whoever owns that connection — one
 * caller at a time, as quiche's own contract already requires.
 */
class QlogRecord internal constructor(
    val directory: QlogDirectory,
    val name: String,
) {
    /** The handshake completed: this record is a connection that carried traffic, not a failed attempt. */
    fun established() = directory.established(name)

    /** The current segment holds [bytesOnDisk]; says whether the next is due, and what the directory dropped. */
    fun observe(bytesOnDisk: Long): QlogStep = directory.observe(name, bytesOnDisk)

    /**
     * quiche now writes [next], and the segment it let go of holds [previousBytes] — measured after
     * the switch, when quiche has flushed it. Returns what the directory dropped to make room.
     */
    fun rotated(
        next: QlogSegment,
        previousBytes: Long,
    ): List<QlogFile> = directory.rotated(name, next, previousBytes)

    /** quiche would not open [next]: the current segment goes on growing, and this record stops rotating. */
    fun rotationRefused(next: QlogSegment) = directory.rotationRefused(name, next)

    /** quiche would not open the head: this connection has no record at all. */
    fun refusedByQuiche() = directory.refusedByQuiche(name)

    /** The connection ended with [bytesOnDisk] in its last segment; returns what the directory dropped. */
    fun close(bytesOnDisk: Long): List<QlogFile> = directory.close(name, bytesOnDisk)
}

/** One file of a qlog record, and the bytes the directory counted for it. */
data class QlogFile(
    val path: String,
    val bytes: Long,
)

/** One segment of a connection's qlog: its 1-based [index] in the connection's run, and where quiche writes it. */
data class QlogSegment(
    val index: Int,
    val path: String,
)

/** A connection with a record in a [QlogDirectory], where it is in its life, and the files it keeps. */
data class QlogRetained(
    val name: String,
    val lifecycle: QlogLifecycle,
    val files: List<QlogFile>,
)

/** Where a connection with a record is in its life. */
sealed interface QlogLifecycle {
    enum class Live : QlogLifecycle { Handshaking, Established }

    data class Closed(
        val how: QlogClosed,
    ) : QlogLifecycle
}

/** How a connection whose record is closed had fared. */
enum class QlogClosed {
    /** Its handshake never completed: a failed attempt. */
    NeverEstablished,

    /** It carried traffic. */
    Established,

    /** An earlier process wrote it; this one found it in the directory. */
    Inherited,
}

/** Which limit made a record give way. */
enum class QlogPressure { ConnectionSegments, DirectoryBytes, DirectoryConnections }

/** Why a connection got no record at all. */
sealed interface QlogRefusal {
    val line: String

    /** Every record the directory may keep belongs to a connection that is still live. */
    data class DirectoryConnections(
        val live: Int,
        val limit: Int,
    ) : QlogRefusal {
        override val line: String get() = "DirectoryConnections(live=$live limit=$limit)"
    }

    /** The heads and current segments of the live connections alone fill the directory. */
    data class DirectoryBytes(
        val liveBytes: Long,
        val limit: Long,
    ) : QlogRefusal {
        override val line: String get() = "DirectoryBytes(liveBytes=$liveBytes limit=$limit)"
    }

    /** Another record already has this name; quiche would refuse the file anyway. */
    data object NameInUse : QlogRefusal {
        override val line: String get() = "NameInUse"
    }
}

/** What becomes of an admitted or refused [QlogDirectory.open]. */
sealed interface QlogOpening {
    /** quiche writes [head] first; [evicted] were dropped to make room and are the caller's to delete. */
    class Admitted(
        val record: QlogRecord,
        val head: QlogSegment,
        val evicted: List<QlogFile>,
    ) : QlogOpening

    data class Refused(
        val reason: QlogRefusal,
    ) : QlogOpening
}

/** The outcome of measuring a record's current segment. [evicted] are the caller's to delete. */
sealed interface QlogStep {
    val evicted: List<QlogFile>

    data class Keep(
        override val evicted: List<QlogFile>,
    ) : QlogStep

    /** The current segment is full: hand quiche [next], then report [QlogRecord.rotated] or [QlogRecord.rotationRefused]. */
    data class RotateDue(
        val next: QlogSegment,
        override val evicted: List<QlogFile>,
    ) : QlogStep
}

/** Everything a [QlogDirectory] reports, each with the line a walk log carries for it. */
sealed interface QlogEvent {
    val line: String

    /** [connection]'s qlog continues in [segment]. */
    data class Segmented(
        val connection: String,
        val segment: QlogSegment,
    ) : QlogEvent {
        override val line: String get() = "QLOG-SEGMENT conn=$connection n=${segment.index} path=${segment.path}"
    }

    /** A middle segment of [connection]'s record was dropped; its head and latest segments remain. */
    data class Truncated(
        val connection: String,
        val dropped: QlogFile,
        val pressure: QlogPressure,
    ) : QlogEvent {
        override val line: String
            get() = "QLOG-TRUNCATED conn=$connection dropped=${dropped.path} bytes=${dropped.bytes} pressure=$pressure"
    }

    /** [connection]'s whole record was dropped. */
    data class Evicted(
        val connection: String,
        val files: List<QlogFile>,
        val closed: QlogClosed,
        val pressure: QlogPressure,
    ) : QlogEvent {
        override val line: String
            get() = "QLOG-EVICTED conn=$connection files=${files.size} bytes=${files.sumOf { it.bytes }} closed=$closed pressure=$pressure"
    }

    /** [connection] got no record. */
    data class Refused(
        val connection: String,
        val reason: QlogRefusal,
    ) : QlogEvent {
        override val line: String get() = "QLOG-REFUSED conn=$connection reason=${reason.line} — this connection has no qlog"
    }

    /** quiche would not create [path], [connection]'s head: a file already there, no directory, or a libquiche without qlog. */
    data class QuicheRefused(
        val connection: String,
        val path: String,
    ) : QlogEvent {
        override val line: String get() = "QLOG-REFUSED conn=$connection reason=QuicheRefused path=$path — this connection has no qlog"
    }

    /** quiche would not create [path]: [connection] keeps writing its current segment, past the budget. */
    data class RotationRefused(
        val connection: String,
        val path: String,
    ) : QlogEvent {
        override val line: String
            get() = "QLOG-ROTATION-REFUSED conn=$connection path=$path — the current segment keeps growing past the budget"
    }

    /** [file] was dropped from the accounts but is still on disk. */
    data class DeleteFailed(
        val file: QlogFile,
    ) : QlogEvent {
        override val line: String
            get() = "QLOG-DELETE-FAILED path=${file.path} bytes=${file.bytes} — the disk holds more than the budget counts"
    }

    /** An earlier process's qlog, now counted against this budget. */
    data class Inherited(
        val connections: Int,
        val files: Int,
        val bytes: Long,
    ) : QlogEvent {
        override val line: String get() = "QLOG-INHERITED connections=$connections files=$files bytes=$bytes"
    }
}
