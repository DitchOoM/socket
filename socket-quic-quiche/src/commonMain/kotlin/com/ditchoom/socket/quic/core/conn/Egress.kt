package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.life.ConnEnd
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
import com.ditchoom.socket.quic.core.udp.Thrown

/** Whether a write collected everything quiche had, the batch filled first, or a due time was another clock's and the response is to end. */
internal enum class Wrote { All, BatchFull, ClockMismatch }

/** What a serve left after a write that ended as [this]. */
internal fun Wrote.collected(): Collected =
    when (this) {
        Wrote.All, Wrote.ClockMismatch -> Collected.Everything
        Wrote.BatchFull -> Collected.MoreWaiting
    }

/** What became of the last datagram quiche wrote: none was written, it is in the batch, or no socket of ours took it. */
internal enum class LastOut { None, Kept, Dropped }

/** Whether quiche has written any datagram of the connection yet: once it has, what it carried may be on the wire. */
internal enum class FirstOut { NotYet, Written }

/**
 * Datagrams out: the only file that asks quiche for output and reads its send infos. Each datagram goes where the
 * connection's [Paths] say: all to one target, or each by the address quiche wrote it for; one for a path no socket
 * of ours holds is dropped and recorded ([DropWhy.NoSocketForPath]). Remembers what became of the last datagram of its
 * last write: the write that makes quiche drain ends with its CONNECTION_CLOSE. Each datagram is recorded at the time
 * quiche stamped it with, which its round trips and probes are measured from and which a replay sends it at ([sentAt]).
 * A write's datagrams leave together, in one batch.
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
    private var first = FirstOut.NotYet
    private var mismatchAhead = 0L

    /** When quiche wants the last datagram it wrote on the wire, on the loop's clock. */
    private var lastDueNanos = 0L

    /** The end of a connection whose write ended as [Wrote.ClockMismatch]: processing failed with a [PacingClockMismatch]. */
    fun mismatchEnd(): ConnEnd = ConnEnd.ProcessingFailed(Thrown(PacingClockMismatch(mismatchAhead)))

    /** Whether quiche has written any datagram of this connection, kept or dropped. */
    fun firstOut(): FirstOut = first

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
        val at = clock.now()
        while (out.room() == Room.Open) {
            if (one(out, paths, flight, at) <= 0) return Wrote.All
        }
        return Wrote.BatchFull
    }

    /**
     * Writes quiche's output into [out] at the pace [pacer] holds it to, judged at the time the write began: quiche's
     * pacer reads its clock when it writes, and a starved turn can begin seconds before its write.
     */
    fun writePaced(
        out: SendBatch,
        paths: Paths,
        pacer: Pacer,
        flight: ConnFlight,
    ): Wrote {
        last = LastOut.None
        val at = clock.now()
        if (pacer.state(at) == PaceState.Holding) return Wrote.All
        while (out.room() == Room.Open) {
            if (one(out, paths, flight, at) <= 0) return Wrote.All
            when (pacer.sent(lastDueNanos, at)) {
                is Due.Now -> Unit
                is Due.Hold -> return Wrote.All
                is Due.ClockMismatch -> if (mismatched(pacer, at, flight) == Wrote.ClockMismatch) return Wrote.ClockMismatch
            }
        }
        return Wrote.BatchFull
    }

    /** A due time on a clock the loop does not read: counted and recorded, then answered as [MismatchResponse] says. */
    private fun mismatched(
        pacer: Pacer,
        at: Nanos,
        flight: ConnFlight,
    ): Wrote {
        counts.pacingClockMismatch()
        flight.pacingClockMismatch(at, pacer.aheadNanos())
        return when (pacer.response()) {
            is MismatchResponse.SendNow -> Wrote.All
            is MismatchResponse.Fail -> {
                mismatchAhead = pacer.aheadNanos()
                Wrote.ClockMismatch
            }
        }
    }

    private fun one(
        out: SendBatch,
        paths: Paths,
        flight: ConnFlight,
        at: Nanos,
    ): Int {
        val offset = out.nextOffset()
        val info = paths.sendInfo()
        val window = paths.window(Window(out.limits.maxDatagramSize))
        val written = q.api.connSend(q.handle, out.storage.address + offset, window.bytes, info)
        if (written <= 0) return written
        first = FirstOut.Written
        lastOffset = offset
        lastLength = written
        lastDueNanos = q.api.sendInfoAtNanos(info)
        val sent = sentAt(at)
        when (val sorting = paths.sorting()) {
            is Sorting.Fixed -> kept(out, sorting.to, sent, flight)
            is Sorting.ByAddress -> {
                // The address that names the datagram's path: a client's own, the peer's for a server.
                val address =
                    when (paths) {
                        is Paths.Client -> q.api.sendInfoFromAddr(info)
                        is Paths.Server -> q.api.sendInfoToAddr(info)
                    }
                when (val to = paths.outgoing(address)) {
                    is Outgoing.To -> kept(out, to, sent, flight)
                    is Outgoing.Dropped -> dropped(address, sent, flight)
                }
            }
        }
        return written
    }

    /**
     * When quiche stamped the datagram it just wrote: its send info's time, quiche's own clock read unless its pacer wants
     * the datagram later, never before the write's start [from]. The write's start is early by every call made before
     * quiche read its clock, a cold one's by milliseconds. A time more than [UNREAD_AHEAD] past [from] may be a pacer's:
     * only then is the clock read, and the datagram is recorded no later than now, when it is in the batch.
     */
    private fun sentAt(from: Nanos): Nanos =
        when {
            lastDueNanos <= from.value -> from
            lastDueNanos - from.value <= UNREAD_AHEAD -> Nanos(lastDueNanos)
            else -> Nanos(minOf(lastDueNanos, maxOf(from.value, clock.now().value)))
        }

    private fun kept(
        out: SendBatch,
        to: Outgoing.To,
        at: Nanos,
        flight: ConnFlight,
    ) {
        last = LastOut.Kept
        lastTarget = to.target
        out.commit(lastLength, to.target)
        flight.datagramOut(at, out.storage, lastOffset, lastLength)
    }

    /** The datagram is for a path no socket of ours holds: it is counted, and recorded with the address naming that path. */
    private fun dropped(
        address: Long,
        at: Nanos,
        flight: ConnFlight,
    ) {
        last = LastOut.Dropped
        counts.datagramDropped()
        val api = q.api
        val family = api.sockAddrFamily(address)
        val port = api.sockAddrPort(address)
        val hi = if (family == V4) 0L else api.sockAddrV6Hi(address)
        val lo = if (family == V4) api.sockAddrV4(address) else api.sockAddrV6Lo(address)
        flight.dropped(at, DropWhy.NoSocketForPath, lastLength, family, port, hi, lo)
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

/** How far past a write's start quiche's send time may be, a warm call's lag, before the clock is read to bound it. */
private const val UNREAD_AHEAD = 250_000L

/** `AF_INET`'s family as the sockaddr readers report it. */
private const val V4 = 4
