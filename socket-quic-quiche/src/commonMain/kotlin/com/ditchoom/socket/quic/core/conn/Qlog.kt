package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.EnvironmentSetting
import com.ditchoom.socket.quic.QlogDeletion
import com.ditchoom.socket.quic.QlogEnvironment
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionId
import com.ditchoom.socket.quic.QuicheApi
import com.ditchoom.socket.quic.QuicheConn
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.deleteQlogFile
import com.ditchoom.socket.quic.environmentSetting
import com.ditchoom.socket.quic.qlogFileBytes
import com.ditchoom.socket.quic.trace.QlogFile
import com.ditchoom.socket.quic.trace.QlogOpening
import com.ditchoom.socket.quic.trace.QlogRecord
import com.ditchoom.socket.quic.trace.QlogSegment
import com.ditchoom.socket.quic.trace.QlogStep
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.quic.trace.record

/** What a connection does about its qlog after quiche was told where to write it. */
internal sealed interface Qlog {
    /** quiche writes one file, or none: there is nothing to measure. */
    data object Unbudgeted : Qlog

    /** quiche writes the segments of a directory that holds them to its budget; [watch] measures and cuts them. */
    class Segmented(
        val watch: SegmentWatch,
    ) : Qlog
}

/**
 * The segments quiche writes for [record], which holds them to its directory's budget. Every [CHECK_SERVES]-th serve measures
 * the segment quiche writes and hands it the next once the budget says the segment is full; the counting is in serves, not
 * time, so a burst is cut as promptly as a trickle and a run on virtual time cuts at the same points.
 */
@Confined
internal class SegmentWatch(
    private val api: QuicheApi,
    private val conn: QuicheConn,
    private val record: QlogRecord,
    private val role: QuicRole,
    private val session: QuicSessionId,
    head: QlogSegment,
) {
    private var current: QlogSegment = head
    private var serves = 0

    /** Inside: the connection was served once more. */
    fun measure() {
        if (++serves < CHECK_SERVES) return
        serves = 0
        when (val step = record.observe(qlogFileBytes(current.path))) {
            is QlogStep.Keep -> deleteDropped(record, step.evicted)
            is QlogStep.RotateDue -> {
                deleteDropped(record, step.evicted)
                if (api.connSetQlogPath(conn, step.next.path, titleOf(role), segmentDescriptionOf(session, step.next))) {
                    val finished = current
                    current = step.next
                    // quiche flushed and closed the segment it let go of as it did, so this is its final size.
                    deleteDropped(record, record.rotated(step.next, qlogFileBytes(finished.path)))
                } else {
                    record.rotationRefused(step.next)
                }
            }
        }
    }

    /** Inside: the handshake completed, so the record is a connection that carried traffic and not a failed attempt. */
    fun reachedEstablished() = record.established()

    /** The connection's memory was freed, which flushed the last segment: settles the record's accounts. */
    fun settle() = deleteDropped(record, record.close(qlogFileBytes(current.path)))

    private companion object {
        /**
         * Serves between measurements of the current segment: one `stat` per this many. A serve writes at most one flush's
         * worth of qlog, so a segment overshoots its size by at most this many flushes.
         */
        const val CHECK_SERVES = 64
    }
}

/** Inside: a serve of the connection ended. */
internal fun Qlog.served() =
    when (this) {
        is Qlog.Unbudgeted -> Unit
        is Qlog.Segmented -> watch.measure()
    }

/** Inside: the connection's handshake completed. */
internal fun Qlog.established() =
    when (this) {
        is Qlog.Unbudgeted -> Unit
        is Qlog.Segmented -> watch.reachedEstablished()
    }

/** After the connection's memory was freed. */
internal fun Qlog.closed() =
    when (this) {
        is Qlog.Unbudgeted -> Unit
        is Qlog.Segmented -> watch.settle()
    }

/**
 * Names a core connection's qlog before its first packet in either direction, on the thread that made it: the capture's
 * own [QlogTarget.File], else the target it names for a directory with a budget ([QlogTarget.Budgeted]), else one file per
 * connection in the `QUIC_QLOG_DIR` directory (JVM/Android: the `quic.qlog.dir` property first), `quiche-<role>-<session>.sqlog`
 * and its segments, else none. A path quiche cannot open, and a budgeted record the directory refuses, are recorded in the
 * trace as refused, never fatal.
 */
internal fun QuicheApi.nameQlog(
    conn: QuicheConn,
    capture: TraceCapture,
    role: QuicRole,
    session: QuicSessionId,
): Qlog {
    val named =
        when (capture) {
            TraceCapture.Off -> QlogTarget.Off
            is TraceCapture.On -> capture.qlog
        }
    val target =
        when (named) {
            is QlogTarget.Off -> environmentTarget(role, session)
            is QlogTarget.File, is QlogTarget.Budgeted -> named
        }
    return when (target) {
        is QlogTarget.Off -> Qlog.Unbudgeted
        is QlogTarget.File -> {
            if (!connSetQlogPath(conn, target.path, titleOf(role), descriptionOf(session))) refused(capture, target.path, role, session)
            Qlog.Unbudgeted
        }
        is QlogTarget.Budgeted ->
            when (val opening = target.directory.open(target.name)) {
                is QlogOpening.Refused -> {
                    refused(capture, target.directory.segmentPath(target.name, 1), role, session)
                    Qlog.Unbudgeted
                }
                is QlogOpening.Admitted -> {
                    deleteDropped(opening.record, opening.evicted)
                    if (connSetQlogPath(conn, opening.head.path, titleOf(role), segmentDescriptionOf(session, opening.head))) {
                        println("[qlog] tracing ${role.label} connection $session to ${opening.head.path}, segmented")
                        Qlog.Segmented(SegmentWatch(this, conn, opening.record, role, session, opening.head))
                    } else {
                        opening.record.refusedByQuiche()
                        refused(capture, opening.head.path, role, session)
                        Qlog.Unbudgeted
                    }
                }
            }
    }
}

/** The environment's directory, held to its budget; named by the session, which quiche opens with `create_new`. */
private fun environmentTarget(
    role: QuicRole,
    session: QuicSessionId,
): QlogTarget =
    when (val dir = environmentSetting("quic.qlog.dir", "QUIC_QLOG_DIR")) {
        is EnvironmentSetting.Unset -> QlogTarget.Off
        is EnvironmentSetting.Value -> QlogEnvironment.target(dir.text, "quiche-${role.label}-$session")
    }

private fun titleOf(role: QuicRole) = "ditchoom-socket ${role.label}"

private fun descriptionOf(session: QuicSessionId) = "session $session"

/** The first segment is described as the connection, later ones by their number. */
private fun segmentDescriptionOf(
    session: QuicSessionId,
    segment: QlogSegment,
) = if (segment.index == 1) descriptionOf(session) else "${descriptionOf(session)} segment ${segment.index}"

private fun deleteDropped(
    record: QlogRecord,
    files: List<QlogFile>,
) {
    for (file in files) {
        if (deleteQlogFile(file.path) == QlogDeletion.Failed) record.directory.deleteFailed(file)
    }
}

private fun refused(
    capture: TraceCapture,
    path: String,
    role: QuicRole,
    session: QuicSessionId,
) {
    println("[qlog] refused $path for ${role.label} connection $session")
    capture.record { it.qlogRefused(path) }
}
