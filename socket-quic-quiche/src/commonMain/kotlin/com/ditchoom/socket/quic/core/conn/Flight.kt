package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.FlightKind
import com.ditchoom.socket.quic.core.observe.FlightRing
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TracePath
import com.ditchoom.socket.testkit.trace.TraceSink
import kotlin.time.Duration.Companion.nanoseconds

/** How large a connection's flight recording is: records held, and bytes of datagram payload kept with them. */
internal class FlightSize(
    val records: Int,
    val payloadBytes: Int,
)

/**
 * A connection's flight recorder. Every datagram in and out, and every network change the connection is told of
 * (written to a trace as a path-state line named for the change, such as `Rehomed`), is recorded on the hot path into a preallocated ring and payload arena, allocating nothing. The recording is
 * written in the trace grammar of [com.ditchoom.socket.quic.trace.QuicTraceRecorder] (v1) only off that path: after
 * a turn when the connection's [TraceCapture] is on, and into any [TraceSink] on demand, so the existing trace
 * tools and the schedule replay read core recordings unchanged. Inside the connection only.
 */
@Confined
internal class ConnFlight(
    size: FlightSize,
    private val arena: NativeBytes,
    peer: PathKey,
    private val capture: TraceCapture,
    private val originNanos: Long,
) {
    private val ring = FlightRing(size.records)
    private val path = TracePath(peer.family, peer.port, peer.hi, peer.lo)
    private val peerId = ring.bindAddress(peer.family, peer.port, peer.hi, peer.lo)
    private val toTrace: TraceSink =
        when (capture) {
            is TraceCapture.Off -> TraceSink { }
            is TraceCapture.On -> TraceSink { capture.recorder.record(it) }
        }
    private var arenaWritten = 0L
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

    fun ended(
        now: Nanos,
        code: Int,
        error: Long,
    ) = ring.ended(now.value, code, error)

    /** Writes what was recorded since the last call to the connection's trace, when the trace is on. */
    fun toTrace() =
        when (capture) {
            is TraceCapture.Off -> Unit
            is TraceCapture.On -> traced = write(traced, toTrace)
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
        val capacity = arena.capacity
        var at = arenaWritten
        var start = (at % capacity).toInt()
        if (start + length > capacity) {
            at += capacity - start
            start = 0
        }
        val kept = minOf(length, capacity)
        arena.copyFrom(bytes, offset, start, kept)
        arenaWritten = at + kept
        ring.datagram(kind, now.value, length, 0, peerId, at, 0L)
    }

    /** Writes the records from absolute record [from] on; returns the absolute count written up to. */
    private fun write(
        from: Long,
        emit: TraceSink,
    ): Long {
        val oldest = ring.dropped
        val total = oldest + ring.size
        for (n in maxOf(from, oldest) until total) {
            val i = (n - oldest).toInt()
            val kind = ring.kindAt(i)
            if (kind == FlightKind.NetworkEvent) {
                emit.emit(TraceEvent.PathState(sinceOrigin(i), NetworkChange.entries[ring.subAt(i)].name, null, 0))
                continue
            }
            if (kind != FlightKind.DatagramIn && kind != FlightKind.DatagramOut) continue
            val at = ring.aAt(i)
            val length = ring.lengthAt(i)
            if (at < arenaWritten - arena.capacity) continue
            val t = sinceOrigin(i)
            val hex = hexOf((at % arena.capacity).toInt(), minOf(length, arena.capacity))
            emit.emit(
                if (kind == FlightKind.DatagramOut) {
                    TraceEvent.DgramOut(t, length, path, hex)
                } else {
                    TraceEvent.DgramIn(t, length, path, hex)
                },
            )
        }
        return total
    }

    private fun sinceOrigin(i: Int) = (ring.nanosAt(i) - originNanos).nanoseconds

    private fun hexOf(
        start: Int,
        length: Int,
    ): String {
        val out = StringBuilder(length * 2)
        for (k in start until start + length) {
            val b = arena.byteAt(k)
            out.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return out.toString()
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
