package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.path.SinglePath
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** Whether a write collected everything quiche had, or the batch filled first. */
internal enum class Wrote { All, BatchFull }

/** Datagrams out: the only file that asks quiche for output and reads its send times. */
internal class Egress(
    private val q: QuicheConnRef,
) {
    /** Writes quiche's output into [out] at once. */
    fun write(
        out: SendBatch,
        path: SinglePath,
        now: Nanos,
        flight: ConnFlight,
    ): Wrote {
        while (out.room() == Room.Open) {
            if (one(out, path, now, flight) <= 0) return Wrote.All
        }
        return Wrote.BatchFull
    }

    /** Writes quiche's output into [out] at the pace [pacer] holds it to. */
    fun writePaced(
        out: SendBatch,
        path: SinglePath,
        now: Nanos,
        pacer: Pacer,
        flight: ConnFlight,
    ): Wrote {
        if (pacer.state(now) == PaceState.Holding) return Wrote.All
        while (out.room() == Room.Open) {
            if (one(out, path, now, flight) <= 0) return Wrote.All
            if (pacer.sent(q.api.sendInfoAtNanos(path.sendInfo), now) == PaceState.Holding) return Wrote.All
        }
        return Wrote.BatchFull
    }

    private fun one(
        out: SendBatch,
        path: SinglePath,
        now: Nanos,
        flight: ConnFlight,
    ): Int {
        val offset = out.nextOffset()
        val written = q.api.connSend(q.handle, out.storage.address + offset, out.limits.maxDatagramSize, path.sendInfo)
        if (written > 0) {
            out.commit(written, path.target)
            flight.datagramOut(now, out.storage, offset, written)
        }
        return written
    }
}
