package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.path.Sorting
import com.ditchoom.socket.quic.core.path.Window
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** Whether a write collected everything quiche had, or the batch filled first. */
internal enum class Wrote { All, BatchFull }

/**
 * Datagrams out: the only file that asks quiche for output and reads its send infos. Each datagram goes where the
 * connection's [Paths] say: all to one target, or each by the address quiche wrote it for; one for a path no socket
 * of ours holds is dropped.
 */
internal class Egress(
    private val q: QuicheConnRef,
    private val counts: ConnCounts,
) {
    /** Writes quiche's output into [out] at once. */
    fun write(
        out: SendBatch,
        paths: Paths,
        now: Nanos,
        flight: ConnFlight,
    ): Wrote {
        while (out.room() == Room.Open) {
            if (one(out, paths, now, flight) <= 0) return Wrote.All
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
        if (pacer.state(now) == PaceState.Holding) return Wrote.All
        while (out.room() == Room.Open) {
            if (one(out, paths, now, flight) <= 0) return Wrote.All
            if (pacer.sent(q.api.sendInfoAtNanos(paths.sendInfo()), now) == PaceState.Holding) return Wrote.All
        }
        return Wrote.BatchFull
    }

    private fun one(
        out: SendBatch,
        paths: Paths,
        now: Nanos,
        flight: ConnFlight,
    ): Int {
        val offset = out.nextOffset()
        val info = paths.sendInfo()
        val window = paths.window(Window(out.limits.maxDatagramSize))
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
        when (to) {
            is Outgoing.To -> {
                out.commit(written, to.target)
                flight.datagramOut(now, out.storage, offset, written)
            }
            is Outgoing.Dropped -> counts.datagramDropped()
        }
        return written
    }
}
