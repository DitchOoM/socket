package com.ditchoom.socket.quic.core.life

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** What a close asks for: a graceful close, which first delivers what was written before it, or an immediate one. */
internal sealed interface CloseChoice {
    /**
     * Delivers everything written before the close point, as [waits] says, then closes with NO_ERROR: as soon as it is
     * delivered, and [limitNanos] after the close at the latest.
     */
    class Graceful(
        val limitNanos: Long,
        val waits: DrainUntil,
    ) : CloseChoice

    /** Sends its CONNECTION_CLOSE with [error] at once; what was not delivered is abandoned. */
    class Immediate(
        val error: QuicError,
    ) : CloseChoice

    companion object {
        /** A graceful close's limit unless the caller chose one. */
        const val DEFAULT_LIMIT_NANOS = 10_000_000_000L
    }
}

/** What a graceful close waits for before its CONNECTION_CLOSE. */
internal enum class DrainUntil {
    /** The peer acknowledged every byte and FIN written on the streams. */
    Acknowledged,

    /** The connection ends on its own: the peer's close, or an idle timeout. */
    PeerDone,
}

/** Whether a datagram reached quiche since the connection last asked whether quiche drains. */
internal enum class Heard { Nothing, Datagram }

/** Whether a connection that just ended keeps its ids for a linger. */
internal enum class AfterEnd { Lingers, Released }

/** What an ended connection's close still holds. */
internal enum class Holding { Quiche, Ids, Nothing }

/** Where a connection's close stands on the wire. Each phase only follows the ones above it. */
internal sealed interface Wire {
    /** No CONNECTION_CLOSE was handed to quiche. */
    data object Quiet : Wire

    /** quiche took the close: the write after which it drains ends with the CONNECTION_CLOSE. */
    data object Sending : Wire

    /** quiche is closing on its own, the peer's close first: nothing of ours goes out. */
    data object Draining : Wire

    /**
     * RFC 9000 §10.2.1 closing state: the close is out; late packets are answered with its [datagrams], kept in [copy],
     * until [until], within what [address] allows, and the close is repeated unprompted as [repeats] says.
     */
    class Answering(
        val copy: NativeBytes,
        val datagrams: List<CloseDatagram>,
        val until: Nanos,
        val repeats: Repeats,
        val address: PeerAddress,
    ) : Wire

    /** Nothing of ours goes out: late packets are dropped until [until]. */
    class Silent(
        val until: Nanos,
    ) : Wire

    /** Nothing is left on the wire: the connection's ids, socket and memory may go. */
    data object Done : Wire
}

/**
 * A connection's close on the wire: the CONNECTION_CLOSE quiche sends, and the linger after it, in which the connection
 * keeps its ids and answers late packets with a copy of that datagram, at most once per doubling of their count
 * (RFC 9000 §10.2.1); after the peer's close, it keeps its ids and drops them (§10.2.2). A sub-part of [ConnLife];
 * inside the connection only.
 */
@Confined
internal class Linger(
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private var wire: Wire = Wire.Quiet
    private var late = 0
    private var owed = 0
    private var heard = Heard.Nothing

    /** Which of the close's datagrams the answer being written sends next. */
    private var piece = 0

    /** What answers toward an unvalidated address may still send: three times the bytes that arrived since the close. */
    private var credit = 0L

    fun wire(): Wire = wire

    /** A datagram reached quiche. */
    fun heard() {
        heard = Heard.Datagram
    }

    /** Whether a datagram reached quiche since the last call. */
    fun takeHeard(): Heard = heard.also { heard = Heard.Nothing }

    /** quiche took the close. */
    fun handedToQuiche() {
        wire = Wire.Sending
    }

    /** Whether quiche's next datagram carries the close. */
    fun sending(): Boolean = wire is Wire.Sending

    /** What the close still holds: quiche, until its close leaves; the ids, while it lingers; or nothing. */
    fun holds(): Holding =
        when (wire) {
            is Wire.Sending -> Holding.Quiche
            is Wire.Answering, is Wire.Silent -> Holding.Ids
            is Wire.Quiet, is Wire.Draining, is Wire.Done -> Holding.Nothing
        }

    fun draining() {
        wire = Wire.Draining
    }

    /**
     * The close's [datagrams] went into [out] at [now]: a copy answers late packets until [until], quiche's own draining
     * time of three probe timeouts, within what [address] allows, and is repeated unprompted only to a validated one.
     */
    fun closeOut(
        out: SendBatch,
        datagrams: List<CloseDatagram>,
        now: Nanos,
        until: Nanos,
        address: PeerAddress,
    ) {
        val buffer = factory.allocate(datagrams.sumOf { it.length })
        late = 0
        owed = 0
        piece = 0
        credit = 0L
        wire =
            when (val made = NativeBytes.of(buffer)) {
                is Addressed.Native -> {
                    var at = 0
                    val kept =
                        datagrams.map { d ->
                            made.bytes.copyFrom(out.storage, d.offset, at, d.length)
                            CloseDatagram(at, d.length, d.target, d.socket).also { at += d.length }
                        }
                    val repeats = Repeats(now, (until - now) / DRAIN_PTOS, until, address)
                    Wire.Answering(made.bytes, kept, until, repeats, address)
                }
                is Addressed.NotAddressable -> {
                    buffer.freeNativeMemory()
                    Wire.Silent(until)
                }
            }
    }

    /** The close went out in a datagram no socket of ours kept, or the peer's close arrived: late packets are dropped until [until]. */
    fun silent(until: Nanos) {
        wire = Wire.Silent(until)
    }

    /** A datagram of [length] bytes for the connection arrived after its end; it is never handed to quiche. */
    fun late(length: Int) {
        counts.lateDatagram()
        when (val w = wire) {
            is Wire.Answering -> {
                w.repeats.stop()
                credit += AMPLIFICATION * length
                late++
                if (late and (late - 1) == 0) owed++
            }
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> Unit
        }
    }

    /** At [now]: a repeat that fell due is owed. */
    fun repeatDue(now: Nanos) {
        when (val w = wire) {
            is Wire.Answering -> if (w.repeats.owed(now) == Repeats.Owed.Yes) owed++
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> Unit
        }
    }

    /**
     * Writes the answers owed into [out] while it has room, each the close's every datagram; [Room.Full] when some are
     * still owed. An answer its allowance does not cover is not sent.
     */
    fun answer(out: SendBatch): Room {
        val answering =
            when (val w = wire) {
                is Wire.Answering -> w
                is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> return Room.Open
            }
        while (owed > 0) {
            if (piece == 0 && !covered(answering)) {
                owed = 0
                return Room.Open
            }
            while (piece < answering.datagrams.size) {
                if (out.room() == Room.Full) return Room.Full
                val d = answering.datagrams[piece]
                out.storage.copyFrom(answering.copy, d.offset, out.nextOffset(), d.length)
                out.commit(d.length, d.target, d.socket)
                piece++
            }
            piece = 0
            owed--
            counts.closeResent()
        }
        return Room.Open
    }

    /** Whether the answer about to go is within what its address allows: an unvalidated one's spends the credit. */
    private fun covered(answering: Wire.Answering): Boolean =
        when (answering.address) {
            PeerAddress.Validated -> true
            PeerAddress.Unvalidated -> {
                val bytes = answering.datagrams.sumOf { it.length }.toLong()
                (credit >= bytes).also { if (it) credit -= bytes }
            }
        }

    /** Whether the linger's time is up at [now]. */
    fun due(now: Nanos): Boolean =
        when (val w = wire) {
            is Wire.Answering -> now >= w.until
            is Wire.Silent -> now >= w.until
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Done -> false
        }

    /** Offers the time the linger ends, and its next repeat. */
    fun offerWake(sink: WakeSink) {
        when (val w = wire) {
            is Wire.Answering -> {
                sink.offer(w.until)
                w.repeats.offerWake(sink)
            }
            is Wire.Silent -> sink.offer(w.until)
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Done -> Unit
        }
    }

    /**
     * The connection ended: a close still to send or sent lingers, and so does a connection the peer closed; a close
     * quiche declined, or no close at all, has nothing left on the wire.
     */
    fun afterEnd(): AfterEnd =
        when (wire) {
            is Wire.Sending, is Wire.Answering, is Wire.Silent -> AfterEnd.Lingers
            is Wire.Quiet, is Wire.Draining, is Wire.Done -> {
                done()
                AfterEnd.Released
            }
        }

    /** Nothing more goes out: the copy is freed. */
    fun done() {
        when (val w = wire) {
            is Wire.Answering -> w.copy.free()
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> Unit
        }
        wire = Wire.Done
    }
}

/** quiche's draining time, in probe timeouts (RFC 9000 §10.2: three). */
private const val DRAIN_PTOS = 3

/** What a server may send an unvalidated address per byte it received from it (RFC 9000 §8.1). */
private const val AMPLIFICATION = 3
