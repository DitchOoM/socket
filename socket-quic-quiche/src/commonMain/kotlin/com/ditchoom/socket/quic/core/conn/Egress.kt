package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.path.Sorting
import com.ditchoom.socket.quic.core.path.Window
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** Whether a write collected everything quiche had, or the batch filled first. */
internal enum class Wrote { All, BatchFull }

/** What became of the last datagram quiche wrote: none was written, it is in the batch, or no socket of ours took it. */
internal enum class LastOut { None, Kept, Dropped }

/**
 * Datagrams out: the only file that asks quiche for output and reads its send infos. Each datagram goes where the
 * connection's [Paths] say: all to one target, or each by the address quiche wrote it for; one for a path no socket
 * of ours holds is dropped. Remembers what became of the last datagram of its last write: the write that makes quiche
 * drain ends with its CONNECTION_CLOSE. Each datagram is recorded at the time quiche was asked for it, read from [clock]:
 * the turn's time can be well before then, as the turn runs its work first, and a replay sends at the recorded time.
 */
@Confined
internal class Egress(
    private val q: QuicheConnRef,
    private val counts: ConnCounts,
    private val clock: LoopClock,
) {
    private var last = LastOut.None
    private var lastOffset = 0
    private var lastLength = 0
    private var lastTarget: SendTarget = SendTarget.ConnectedPeer

    /** What became of the last datagram quiche wrote in the last write. */
    fun last(): LastOut = last

    /** Where in its batch the last write wrote its last datagram, kept or not; meaningful unless [last] is [LastOut.None]. */
    fun lastOffset(): Int = lastOffset

    fun lastLength(): Int = lastLength

    /** Where the last datagram went, while [last] is [LastOut.Kept]. */
    fun lastTarget(): SendTarget = lastTarget

    /** Writes quiche's output into [out] at once. */
    fun write(
        out: SendBatch,
        paths: Paths,
        flight: ConnFlight,
    ): Wrote {
        last = LastOut.None
        while (out.room() == Room.Open) {
            if (one(out, paths, flight) <= 0) return Wrote.All
        }
        return Wrote.BatchFull
    }

    /** Writes quiche's output into [out] at the pace [pacer] holds it to. */
    fun writePaced(
        out: SendBatch,
        paths: Paths,
        now: Nanos,
        pacer: Pacer,
        flight: ConnFlight,
    ): Wrote {
        last = LastOut.None
        if (pacer.state(now) == PaceState.Holding) return Wrote.All
        while (out.room() == Room.Open) {
            if (one(out, paths, flight) <= 0) return Wrote.All
            if (pacer.sent(q.api.sendInfoAtNanos(paths.sendInfo()), now) == PaceState.Holding) return Wrote.All
        }
        return Wrote.BatchFull
    }

    private fun one(
        out: SendBatch,
        paths: Paths,
        flight: ConnFlight,
    ): Int {
        val offset = out.nextOffset()
        val info = paths.sendInfo()
        val window = paths.window(Window(out.limits.maxDatagramSize))
        val at = clock.now()
        val written = q.api.connSend(q.handle, out.storage.address + offset, window.bytes, info)
        if (written <= 0) return written
        val to =
            when (val sorting = paths.sorting()) {
                is Sorting.Fixed -> sorting.to
                is Sorting.ByAddress ->
                    when (paths) {
                        is Paths.Client -> paths.paths.outgoing(q.api.sendInfoFromAddr(info))
                        is Paths.Server -> paths.paths.outgoing(q.api.sendInfoToAddr(info))
                    }
            }
        lastOffset = offset
        lastLength = written
        when (to) {
            is Outgoing.To -> {
                last = LastOut.Kept
                lastTarget = to.target
                out.commit(written, to.target)
                flight.datagramOut(at, out.storage, offset, written)
            }
            is Outgoing.Dropped -> {
                last = LastOut.Dropped
                counts.datagramDropped()
            }
        }
        return written
    }

    /**
     * The last datagram, which no socket of ours held, goes to [target] after all: it is still where it was written,
     * the batch's next slot, since nothing came after it.
     */
    fun sendDroppedTo(
        out: SendBatch,
        target: SendTarget,
        flight: ConnFlight,
    ) {
        last = LastOut.Kept
        lastTarget = target
        out.commit(lastLength, target)
        flight.datagramOut(clock.now(), out.storage, lastOffset, lastLength)
    }
}
