package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.EndedFlight
import com.ditchoom.socket.quic.core.observe.Evidence
import com.ditchoom.socket.quic.core.observe.FlightEvidence
import com.ditchoom.socket.quic.core.observe.FlightKind
import com.ditchoom.socket.quic.core.observe.FlightRing
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.time.Duration.Companion.nanoseconds

/** A change of a connection's path, as its flight recording names it. */
internal enum class PathMark {
    /** The client's loop opened a socket on a new local address and the path from it is probed. */
    Moved,

    /** The probed path was validated and the connection migrated to it. */
    Migrated,

    /** A move was refused, the connection staying on its socket: it could not follow, open, or keep the new one. */
    Refused,
    OpenRefused,

    /** A move was refused because the caller lent the socket that carries the connection, or its io opens no second. */
    LentRefused,
    NoSecond,
    SecondLost,

    /** Quiche gave up validating the probed path or closed it: it is probed again later, or the move is given up. */
    ProbeFailed,
    Abandoned,

    /** A stranded endpoint was probed again, or tried to be. */
    Reprobed,

    /** The path being validated was asked for a fresh PATH_CHALLENGE. */
    Challenged,

    /** The client's socket was wanted on another address and waits where it is for a spare id from the peer. */
    Held,

    /** The active path stopped answering ([com.ditchoom.socket.quic.core.path.PathNews.Silent]), or answered again. */
    Silent,
    Answering,

    /** A read waited on a quiet path with nothing in flight: the client sent one PING (the awaiting-peer probe). */
    Pinged,

    /** A silent path asked for a fresh port, or answered first, was served, held or forbidden; or gave its move up. */
    Rebind,
    RebindStale,
    RebindServed,
    RebindHeld,
    RebindForbidden,
    RebindGaveUp,
}

/** The trace's [TraceEvent.Error.type] for a [PacingClockMismatch]: its qualified name. */
private const val PACING_MISMATCH_TYPE = "com.ditchoom.socket.quic.core.conn.PacingClockMismatch"

/** How many records a connection's flight recording holds; their payload is kept in its loop's [FlightArena]. */
internal class FlightSize(
    val records: Int,
)

/**
 * A connection's flight recorder. Every datagram in and out, and every network change the connection is told of
 * (written to a trace as a path-state line named for the change, such as `Rehomed`), is recorded on the hot path into a
 * preallocated ring, and its payload into the loop's shared [FlightArena], allocating nothing. The recording is written
 * in the trace grammar of [com.ditchoom.socket.quic.trace.QuicTraceRecorder] (v1) only off that path: after a turn when
 * the connection's [TraceCapture] is on, and into any [TraceSink] on demand, so the existing trace tools and the
 * schedule replay read core recordings unchanged. Inside the connection only.
 */
@Confined
internal class ConnFlight(
    size: FlightSize,
    private val arena: FlightArena,
    peer: PathKey,
    private val capture: TraceCapture,
    private val originNanos: Long,
) {
    private val ring = FlightRing(size.records)
    private val path = TracePath(peer.family, peer.port, peer.hi, peer.lo)
    private val peerKey = peer
    private val peerId = ring.bindAddress(peer.family, peer.port, peer.hi, peer.lo)
    private val toTrace: TraceSink =
        when (capture) {
            is TraceCapture.Off -> TraceSink { }
            is TraceCapture.On -> TraceSink { capture.recorder.record(it) }
        }
    private var traced = 0L

    fun datagramIn(
        now: Nanos,
        bytes: NativeBytes,
        offset: Int,
        length: Int,
    ) = datagram(FlightKind.DatagramIn, now, bytes, offset, length)

    fun datagramOut(
        now: Nanos,
        bytes: NativeBytes,
        offset: Int,
        length: Int,
    ) = datagram(FlightKind.DatagramOut, now, bytes, offset, length)

    /** The connection was told the network changed: [code] is the change's ordinal. */
    fun network(
        now: Nanos,
        code: Int,
    ) = ring.event(FlightKind.NetworkEvent, now.value, code, 0, peerId, 0L, 0L)

    /** A datagram of [length] bytes, for or from the address [family]/[port]/[hi]/[lo], was dropped for [why]. */
    fun dropped(
        now: Nanos,
        why: DropWhy,
        length: Int,
        family: Int,
        port: Int,
        hi: Long,
        lo: Long,
    ) = ring.put(FlightKind.DatagramDropped, now.value, why.ordinal, ring.bindAddress(family, port, hi, lo), peerId, length, 0L, 0L)

    /** The first datagram was stamped [offsetNanos] off the loop's clock at [now] (ahead when positive): another clock's. */
    fun pacingClockMismatch(
        now: Nanos,
        offsetNanos: Long,
    ) = ring.event(FlightKind.PacingClockMismatch, now.value, 0, 0, peerId, offsetNanos, 0L)

    /** The connection's deadline fell due and quiche's timeout runs at [now]. */
    fun timerFired(now: Nanos) = ring.event(FlightKind.TimerFire, now.value, 0, 0, peerId, 0L, 0L)

    /** The connection's path changed as [mark] says. */
    fun path(
        now: Nanos,
        mark: PathMark,
    ) = ring.event(FlightKind.QuichePathEvent, now.value, mark.ordinal, 0, peerId, 0L, 0L)

    fun ended(
        now: Nanos,
        code: Int,
        error: Long,
    ) = ring.ended(now.value, code, error)

    /**
     * The connection ended: when [FlightEvidence] is keeping, the whole recording is handed to it while the payload is
     * still in the arena. Costs one read when it is not.
     */
    fun keepEnded(
        role: QuicRole,
        end: ConnEnd,
    ) = when (FlightEvidence.current()) {
        is Evidence.Off -> Unit
        is Evidence.Keeping -> {
            val events = ArrayList<TraceEvent>()
            write(0L, TraceSink { events += it })
            FlightEvidence.add(EndedFlight(role, peerKey, events, end::class.simpleName.orEmpty()))
        }
    }

    /** Writes what was recorded since the last call to the connection's trace, when the trace is on. */
    fun toTrace() =
        when (capture) {
            is TraceCapture.Off -> Unit
            is TraceCapture.On -> traced = write(traced, toTrace)
        }

    /** The newest record, seen at [now]. */
    fun tail(now: Nanos): FlightTail =
        when (val held = ring.size) {
            0 -> FlightTail.Empty
            else -> FlightTail.Last(ring.kindAt(held - 1), (now.value - ring.nanosAt(held - 1)).nanoseconds, held, ring.dropped)
        }

    /** Writes every record the ring still holds into [sink]. */
    fun dump(sink: TraceSink) {
        write(0L, sink)
    }

    private fun datagram(
        kind: FlightKind,
        now: Nanos,
        bytes: NativeBytes,
        offset: Int,
        length: Int,
    ) {
        val at = arena.write(bytes, offset, length)
        ring.datagram(kind, now.value, length, 0, peerId, at, 0L)
        when (capture) {
            is TraceCapture.Off -> Unit
            // A trace that is on takes the datagram from its own bytes now: the arena is shared by the loop's
            // connections, and their writes may reach this payload before the turn ends.
            is TraceCapture.On -> {
                val total = ring.dropped + ring.size
                write(traced, toTrace, total - 1)
                emitDatagram(kind, ring.size - 1, length, hexOf(bytes, offset, minOf(length, arena.capacity)), toTrace)
                traced = total
            }
        }
    }

    /** Writes the records from absolute record [from] up to [until]; returns the absolute count written up to. */
    private fun write(
        from: Long,
        emit: TraceSink,
        until: Long = ring.dropped + ring.size,
    ): Long {
        val oldest = ring.dropped
        val total = until
        for (n in maxOf(from, oldest) until total) {
            val i = (n - oldest).toInt()
            val kind = ring.kindAt(i)
            if (kind == FlightKind.NetworkEvent) {
                emit.emit(TraceEvent.PathState(sinceOrigin(i), NetworkChange.entries[ring.subAt(i)].name, null, 0))
                continue
            }
            if (kind == FlightKind.TimerFire) {
                emit.emit(TraceEvent.TimerFired(sinceOrigin(i)))
                continue
            }
            if (kind == FlightKind.QuichePathEvent) {
                emit.emit(TraceEvent.PathState(sinceOrigin(i), PathMark.entries[ring.subAt(i)].name, null, 0))
                continue
            }
            if (kind == FlightKind.DatagramDropped) {
                emit.emit(droppedAt(i))
                continue
            }
            if (kind == FlightKind.PacingClockMismatch) {
                emit.emit(TraceEvent.Error(sinceOrigin(i), PACING_MISMATCH_TYPE, mismatchText(ring.aAt(i))))
                continue
            }
            if (kind != FlightKind.DatagramIn && kind != FlightKind.DatagramOut) continue
            val at = ring.aAt(i)
            val length = ring.lengthAt(i)
            val hex =
                when (val read = arena.hex(at, length)) {
                    is ArenaRead.Hex -> read.text
                    is ArenaRead.Overwritten -> continue
                }
            emitDatagram(kind, i, length, hex, emit)
        }
        return total
    }

    private fun droppedAt(i: Int): TraceEvent.Drop {
        val id = ring.localAt(i)
        val hi = ring.hiOf(id).toULong().toString(HEX_RADIX)
        val lo = ring.loOf(id).toULong().toString(HEX_RADIX)
        val address = "${ring.familyOf(id)}:${ring.portOf(id)}:$hi:$lo"
        return TraceEvent.Drop(sinceOrigin(i), DropWhy.entries[ring.subAt(i)].type, "${ring.lengthAt(i)} bytes, address $address")
    }

    private fun mismatchText(offsetNanos: Long) = "the first datagram's send time was $offsetNanos ns off the loop's clock"

    private fun sinceOrigin(i: Int) = (ring.nanosAt(i) - originNanos).nanoseconds

    private fun emitDatagram(
        kind: FlightKind,
        i: Int,
        length: Int,
        hex: String,
        emit: TraceSink,
    ) {
        val t = sinceOrigin(i)
        emit.emit(
            if (kind == FlightKind.DatagramOut) {
                TraceEvent.DgramOut(t, length, path, hex)
            } else {
                TraceEvent.DgramIn(t, length, path, hex)
            },
        )
    }

    private fun hexOf(
        bytes: NativeBytes,
        offset: Int,
        length: Int,
    ): String {
        val out = StringBuilder(length * 2)
        for (k in offset until offset + length) {
            val b = bytes.byteAt(k)
            out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return out.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
        const val HEX_RADIX = 16
    }
}
