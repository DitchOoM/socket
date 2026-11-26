package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.core.life.CloseDatagram
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.path.CONNECTED_PEER
import com.ditchoom.socket.quic.core.path.Outgoing
import com.ditchoom.socket.quic.core.path.Sorting
import com.ditchoom.socket.quic.core.path.Window
import com.ditchoom.socket.quic.core.time.LoopClock
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** What became of the last datagram quiche wrote: none was written, it is in the batch, or no socket of ours took it. */
internal enum class LastOut { None, Kept, Dropped }

/** Whether quiche has written any datagram of the connection yet: once it has, what it carried may be on the wire. */
internal enum class FirstOut { NotYet, Written }

/**
 * Datagrams out: the only file that asks quiche for output and reads its send infos. Each datagram goes where the connection's [Paths] say:
 * all to one target, or each by the address quiche wrote it for; one for a path no socket of ours holds is dropped and recorded
 * ([DropWhy.NoSocketForPath]). Remembers what became of the last datagram of its last write: the write that makes quiche drain ends with
 * its CONNECTION_CLOSE. Each datagram is recorded at the time quiche stamped it with, which its round trips and probes are measured from
 * and which a replay sends it at ([sentAt]). A write's datagrams leave together, in one batch.
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
    private var lastTo = CONNECTED_PEER

    /** The datagram the write kept before its last, which a close before confirmation writes its Handshake packet in. */
    private var before = LastOut.None
    private var beforeOffset = 0
    private var beforeLength = 0
    private var beforeTo = CONNECTED_PEER
    private var first = FirstOut.NotYet
    private var mismatchOffset = 0L
    private val feed = EgressFeed()
    private var refusedCode = 0

    /** When quiche wants the last datagram it wrote on the wire, on the loop's clock. */
    private var lastDueNanos = 0L

    /** Why a write ended as [Wrote.ClockMismatch]: libquiche paces on another clock, [mismatchOffset] apart. */
    fun mismatch(): PacingClockMismatch = PacingClockMismatch(mismatchOffset)

    /** What quiche refused a queued datagram with, once a write ended as [Wrote.DatagramRefused]. */
    fun datagramRefusal(): DatagramRefusedByQuiche = DatagramRefusedByQuiche(feed.refusedCode)

    /** What quiche refused the last write with, once it ended as [Wrote.Refused]. */
    fun refusal(): QuicheSendRefused = QuicheSendRefused(refusedCode)

    /** Whether quiche has written any datagram of this connection, kept or dropped. */
    fun firstOut(): FirstOut = first

    /** What became of the last datagram quiche wrote in the last write. */
    fun last(): LastOut = last

    /**
     * The datagrams of the close the last write ended with, while [last] is [LastOut.Kept]: its last, and before the
     * handshake is [confirmation], the long-header datagram before a 1-RTT last, which carries the Handshake packet's copy.
     */
    fun closeDatagrams(
        out: SendBatch,
        confirmation: Confirmation,
    ): List<CloseDatagram> {
        val final = CloseDatagram(lastOffset, lastLength, lastTo.target, lastTo.socket)
        val split =
            when (confirmation) {
                Confirmation.Pending ->
                    before == LastOut.Kept && longHeader(out, beforeOffset) && !longHeader(out, lastOffset)
                Confirmation.Confirmed -> false
            }
        return if (split) listOf(CloseDatagram(beforeOffset, beforeLength, beforeTo.target, beforeTo.socket), final) else listOf(final)
    }

    private fun longHeader(
        out: SendBatch,
        offset: Int,
    ): Boolean = out.storage.byteAt(offset) and LONG_HEADER != 0

    /** Writes quiche's output into [out] at once; each time it runs dry, [datagrams] queued to send refill it. */
    fun write(
        out: SendBatch,
        paths: Paths,
        flight: ConnFlight,
        datagrams: Datagrams,
    ): Wrote {
        last = LastOut.None
        before = LastOut.None
        val at = clock.now()
        if (feed.fed(datagrams, at) == Dry.Refused) return Wrote.DatagramRefused
        while (out.room() == Room.Open) {
            one(out, paths, flight, at).fold(
                done = {
                    when (feed.fed(datagrams, at)) {
                        Dry.No -> Unit
                        Dry.Yes -> return Wrote.All
                        Dry.Refused -> return Wrote.DatagramRefused
                    }
                },
                wrote = { datagrams.quicheWrote() },
                refused = { return refused(it) },
            )
        }
        return Wrote.BatchFull
    }

    private fun refused(code: Int): Wrote {
        refusedCode = code
        return Wrote.Refused
    }

    /**
     * Writes quiche's output into [out] at the pace [pacer] holds it to, judged at the time the write began: quiche's
     * pacer reads its clock when it writes, and a starved turn can begin seconds before its write. The connection's
     * first datagram judges the clocks first; a connection on another clock that does not end is written unpaced.
     */
    fun writePaced(
        out: SendBatch,
        paths: Paths,
        pacer: Pacer,
        flight: ConnFlight,
        datagrams: Datagrams,
    ): Wrote {
        last = LastOut.None
        before = LastOut.None
        val at = clock.now()
        if (feed.fed(datagrams, at) == Dry.Refused) return Wrote.DatagramRefused
        if (pacer.state(at) == PaceState.Holding) return Wrote.All
        while (out.room() == Room.Open) {
            one(out, paths, flight, at).fold(
                done = {
                    when (feed.fed(datagrams, at)) {
                        Dry.No -> Unit
                        Dry.Yes -> return Wrote.All
                        Dry.Refused -> return Wrote.DatagramRefused
                    }
                },
                wrote = { datagrams.quicheWrote() },
                refused = { return refused(it) },
            )
            when (judged(pacer, at, flight)) {
                is ClockVerdict.Differs ->
                    when (pacer.response()) {
                        is MismatchResponse.Unpaced -> Unit
                        is MismatchResponse.Fail -> return Wrote.ClockMismatch
                    }
                is ClockVerdict.Unjudged, is ClockVerdict.Pinned, is ClockVerdict.Agrees ->
                    when (pacer.sent(lastDueNanos, at)) {
                        is Due.Now -> Unit
                        is Due.Hold -> return Wrote.All
                    }
            }
        }
        return Wrote.BatchFull
    }

    /**
     * The connection's clock verdict, judged by the datagram just written when it is the first; the write began at [at]
     * and the clock is read again now, so the readings bracket quiche's. A mismatch is counted and recorded then, once.
     */
    private fun judged(
        pacer: Pacer,
        at: Nanos,
        flight: ConnFlight,
    ): ClockVerdict =
        when (val v = pacer.verdict()) {
            is ClockVerdict.Unjudged ->
                when (val judged = pacer.judge(lastDueNanos, at, clock.now())) {
                    is ClockVerdict.Differs -> judged.also { mismatched(it, at, flight) }
                    is ClockVerdict.Unjudged, is ClockVerdict.Pinned, is ClockVerdict.Agrees -> judged
                }
            is ClockVerdict.Pinned, is ClockVerdict.Agrees, is ClockVerdict.Differs -> v
        }

    private fun mismatched(
        verdict: ClockVerdict.Differs,
        at: Nanos,
        flight: ConnFlight,
    ) {
        counts.pacingClockMismatch()
        flight.pacingClockMismatch(at, verdict.offsetNanos)
        mismatchOffset = verdict.offsetNanos
    }

    private fun one(
        out: SendBatch,
        paths: Paths,
        flight: ConnFlight,
        at: Nanos,
    ): DatagramStep {
        val offset = out.nextOffset()
        val info = paths.sendInfo()
        val window = paths.window(Window(out.limits.maxDatagramSize))
        val step = DatagramStep(q.api.connSend(q.handle, out.storage.address + offset, window.bytes, info))
        val written = step.fold(done = { return step }, wrote = { it }, refused = { return step })
        first = FirstOut.Written
        before = last
        beforeOffset = lastOffset
        beforeLength = lastLength
        beforeTo = lastTo
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
        return step
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
        lastTo = to
        out.commit(lastLength, to.target, to.socket)
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
     * The last datagram, which no socket of ours held, goes as [to] says after all: it is still where it was written,
     * the batch's next slot, since nothing came after it.
     */
    fun sendDroppedTo(
        out: SendBatch,
        to: Outgoing.To,
        flight: ConnFlight,
    ) {
        last = LastOut.Kept
        lastTo = to
        out.commit(lastLength, to.target, to.socket)
        flight.datagramOut(clock.now(), out.storage, lastOffset, lastLength)
    }
}

/** How far past a write's start quiche's send time may be, a warm call's lag, before the clock is read to bound it. */
private const val UNREAD_AHEAD = 250_000L

/** `AF_INET`'s family as the sockaddr readers report it. */
private const val V4 = 4

/** The header form bit of a QUIC packet's first byte: set in a long header (RFC 9000 §17.2). */
private const val LONG_HEADER = 0x80
