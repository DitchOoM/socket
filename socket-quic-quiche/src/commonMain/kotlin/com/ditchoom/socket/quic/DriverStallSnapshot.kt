package com.ditchoom.socket.quic

import kotlin.time.Duration

/**
 * One connection's driver as its loop saw it at the moment a stalled test asked, read on the loop by
 * [QuicheDriver.stallSnapshot] before that request's own wake runs.
 *
 * A test that stops making progress can be waiting on any of several parties: quiche holding bytes no
 * reader was woken for, a reader parked with its wake already spent, a send skipped behind a stalled
 * path, a reader loop that ended, or a timer the loop never armed. Each is a field here, so a stall
 * names which one it was. Diagnostics only: production never asks for it.
 */
internal data class DriverStallSnapshot(
    val role: QuicRole,
    val state: QuicConnectionState,
    /** The wake the loop armed for the wait this snapshot's request ended, and how long ago. */
    val loopWake: LoopWake,
    /** quiche's own next timer, read now. */
    val quicheTimer: QuicheTimer,
    /** Streams quiche reports readable: bytes or an end it holds for the application. */
    val readable: List<Long>,
    /** Streams quiche reports writable. */
    val writable: List<Long>,
    val sentStreamData: SentStreamData,
    val stats: QuicConnStats?,
    /** The path quiche sends on, found by its active flag (index 0 is the original path, which a move leaves). */
    val activePath: QuicPathStats?,
    /** quiche's own view of every path it holds, by index: validation, loss and recovery counters. */
    val quichePaths: List<QuicPathStats>,
    val ids: ConnectionIdRoom,
    val peerPathRoom: PeerPathRoom,
    val streams: List<StreamWake>,
    /** The driver's paths: those it sends or probes on, then the left ones it still reads ([PathUse.Left]). */
    val paths: List<PathView>,
)

/** The connection ids a migration can spend, as quiche counts them now. */
internal data class ConnectionIdRoom(
    /** The peer's ids no path uses: what a probe to a new 4-tuple needs. */
    val spareDestinationIds: Long,
    /** Our ids the peer holds. */
    val activeSourceIds: Int,
    /** How many more of our ids the peer's limit allows. */
    val sourceIdsLeft: Long,
)

/** The deadline a driver loop was waiting on. */
internal sealed interface LoopWake {
    /** No timer: the loop waited on commands alone. */
    data object Untimed : LoopWake

    data class Timed(
        val kind: WakeKind,
        val wait: Duration,
        val armedAgo: Duration,
    ) : LoopWake
}

internal enum class WakeKind { ProbeAbandon, KeepAlive, QuicheTimeout, SilenceVerdict }

internal sealed interface QuicheTimer {
    data object Unarmed : QuicheTimer

    data class Due(
        val remaining: Duration,
    ) : QuicheTimer
}

/** Whether a conflated wake channel holds a signal no receiver has taken yet. */
internal enum class WakeSignal { Buffered, Spent }

/** Whether a stream's queue of already-drained chunks holds any. */
internal enum class ChunkQueue { Holding, Empty }

/** One stream's read state and the two signals that wake its reader and its writer. */
internal data class StreamWake(
    val id: Long,
    val read: StreamReadState,
    val end: StreamEnd,
    val drainedChunks: ChunkQueue,
    val readWake: WakeSignal,
    val writeWake: WakeSignal,
)

internal enum class PathEgressKind { Open, Stalled, Shut }

/** A path's reader loop: none on a server connection, which the shared socket feeds. */
internal enum class PathReader { None, Running, Ended }

internal data class PathView(
    val key: PathKey,
    val egress: PathEgressKind,
    val reader: PathReader,
    val slot: PathSlot,
    val use: PathUse,
) {
    override fun toString(): String = "PathView(key=$key, egress=$egress, reader=$reader, slot=${slot.describe()}, use=$use)"
}

private fun PathSlot.describe(): String =
    when (this) {
        is PathSlot.Probing -> "Probing(dcidSeq=$dcidSeq)"
        is PathSlot.Unanswered -> "Unanswered(dcidSeq=$dcidSeq, local=$localEndpoint)"
        is PathSlot.Answered -> "Answered(dcidSeq=$dcidSeq, local=$localEndpoint)"
        is PathSlot.Validated -> "Validated(dcidSeq=$dcidSeq)"
        is PathSlot.Active -> "Active(dcidSeq=$dcidSeq)"
        PathSlot.Abandoned -> "Abandoned"
    }

/** Whether a path is the one the connection sends on, another it may move to, or one it left and still reads. */
internal enum class PathUse { Active, Standby, Left }
