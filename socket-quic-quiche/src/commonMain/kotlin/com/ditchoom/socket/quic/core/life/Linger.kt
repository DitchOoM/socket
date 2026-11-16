package com.ditchoom.socket.quic.core.life

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.socket.quic.QuicError
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.ConnCounts
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.Addressed
import com.ditchoom.socket.quic.core.udp.NativeBytes
import com.ditchoom.socket.quic.core.udp.Room
import com.ditchoom.socket.quic.core.udp.SendBatch

/** When a graceful close sends its CONNECTION_CLOSE. */
internal sealed interface CloseRule {
    /** At once. */
    data object Immediate : CloseRule

    /** Once the peer acknowledged every byte and FIN written on the streams, at most [boundNanos] after the close. */
    class UntilAcknowledged(
        val boundNanos: Long,
    ) : CloseRule

    /** Once the connection ends on its own (the peer's close, an idle timeout), at most [boundNanos] after the close. */
    class UntilPeerDone(
        val boundNanos: Long,
    ) : CloseRule
}

/** Whether a close follows the [CloseRule] or sends its CONNECTION_CLOSE at once. */
internal enum class CloseHow { Graceful, Now }

/** What a close that just began does first. */
internal enum class Begun { Flushing, SendNow }

/** Whether a connection that just ended keeps its ids for a linger. */
internal enum class AfterEnd { Lingers, Released }

/** What an ended connection's close still holds. */
internal enum class Holding { Quiche, Ids, Nothing }

/** Where a connection's close stands on the wire. Each phase only follows the ones above it. */
internal sealed interface Wire {
    /** No close was asked. */
    data object Quiet : Wire

    /** A graceful close holds its CONNECTION_CLOSE, with [error], until what was written is delivered or [until]. */
    class Flushing(
        val error: QuicError,
        val until: Nanos,
    ) : Wire

    /** quiche took the close: the write after which it drains ends with the CONNECTION_CLOSE. */
    data object Sending : Wire

    /** quiche is closing on its own, the peer's close first: nothing of ours goes out. */
    data object Draining : Wire

    /** RFC 9000 §10.2.1 closing state: the close is out; late packets are answered with [copy] until [until]. */
    class Answering(
        val copy: NativeBytes,
        val length: Int,
        val target: SendTarget,
        val until: Nanos,
    ) : Wire

    /** The close is out with no copy kept: late packets are dropped until [until]. */
    class Silent(
        val until: Nanos,
    ) : Wire

    /** Nothing is left on the wire: the connection's ids, socket and memory may go. */
    data object Done : Wire
}

/**
 * A connection's close on the wire: the flush a graceful close waits for, the CONNECTION_CLOSE quiche sends, and the
 * linger after it, in which the connection keeps its ids and answers late packets with a copy of that datagram, at
 * most once per doubling of their count (RFC 9000 §10.2.1). A sub-part of [ConnLife]; inside the connection only.
 */
@Confined
internal class Linger(
    private val q: QuicheConnRef,
    private val rule: CloseRule,
    private val factory: BufferFactory,
    private val counts: ConnCounts,
) {
    private var wire: Wire = Wire.Quiet
    private var late = 0
    private var owed = 0

    fun wire(): Wire = wire

    /** A close asked at [now] with [error]: a graceful one waits as the rule says, any other sends at once. */
    fun begin(
        error: QuicError,
        how: CloseHow,
        now: Nanos,
    ): Begun {
        val bound =
            when (how) {
                CloseHow.Now -> return Begun.SendNow
                CloseHow.Graceful ->
                    when (val r = rule) {
                        is CloseRule.Immediate -> return Begun.SendNow
                        is CloseRule.UntilAcknowledged -> r.boundNanos
                        is CloseRule.UntilPeerDone -> r.boundNanos
                    }
            }
        wire = Wire.Flushing(error, now + bound)
        return Begun.Flushing
    }

    /** Whether [flushing] is over at [now]: its bound passed, or everything written was acknowledged when the rule waits for that. */
    fun flushOver(
        flushing: Wire.Flushing,
        now: Nanos,
    ): Boolean =
        now >= flushing.until ||
            when (rule) {
                is CloseRule.UntilAcknowledged -> !q.api.connStreamDataUnacknowledged(q.handle)
                is CloseRule.Immediate, is CloseRule.UntilPeerDone -> false
            }

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
            is Wire.Quiet, is Wire.Flushing, is Wire.Draining, is Wire.Done -> Holding.Nothing
        }

    fun draining() {
        wire = Wire.Draining
    }

    /**
     * The close's datagram went into [out] at [offset], [length] bytes to [target]: a copy answers late packets until
     * [until], quiche's own draining time.
     */
    fun closeOut(
        out: SendBatch,
        offset: Int,
        length: Int,
        target: SendTarget,
        until: Nanos,
    ) {
        val buffer = factory.allocate(length)
        late = 0
        owed = 0
        wire =
            when (val made = NativeBytes.of(buffer)) {
                is Addressed.Native -> {
                    made.bytes.copyFrom(out.storage, offset, 0, length)
                    Wire.Answering(made.bytes, length, target, until)
                }
                is Addressed.NotAddressable -> {
                    buffer.freeNativeMemory()
                    Wire.Silent(until)
                }
            }
    }

    /** The close went out in a datagram no socket of ours kept: late packets are dropped until [until]. */
    fun silent(until: Nanos) {
        wire = Wire.Silent(until)
    }

    /** A datagram for the connection arrived after its end; it is never handed to quiche. */
    fun late() {
        counts.lateDatagram()
        when (wire) {
            is Wire.Answering -> {
                late++
                if (late and (late - 1) == 0) owed++
            }
            is Wire.Quiet, is Wire.Flushing, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> Unit
        }
    }

    /** Writes the answers owed into [out] while it has room; [Room.Full] when some are still owed. */
    fun answer(out: SendBatch): Room {
        val answering =
            when (val w = wire) {
                is Wire.Answering -> w
                is Wire.Quiet, is Wire.Flushing, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> return Room.Open
            }
        while (owed > 0) {
            if (out.room() == Room.Full) return Room.Full
            out.storage.copyFrom(answering.copy, 0, out.nextOffset(), answering.length)
            out.commit(answering.length, answering.target)
            owed--
            counts.closeResent()
        }
        return Room.Open
    }

    /** Whether the linger's time is up at [now]. */
    fun due(now: Nanos): Boolean =
        when (val w = wire) {
            is Wire.Answering -> now >= w.until
            is Wire.Silent -> now >= w.until
            is Wire.Quiet, is Wire.Flushing, is Wire.Sending, is Wire.Draining, is Wire.Done -> false
        }

    /** Offers the time the close next needs the connection served: a flush's bound, or the linger's end. */
    fun offerWake(sink: WakeSink) {
        when (val w = wire) {
            is Wire.Flushing -> sink.offer(w.until)
            is Wire.Answering -> sink.offer(w.until)
            is Wire.Silent -> sink.offer(w.until)
            is Wire.Quiet, is Wire.Sending, is Wire.Draining, is Wire.Done -> Unit
        }
    }

    /**
     * The connection ended: a close still to send, or sent, lingers; a flush or a drain the end cut short, or no close
     * at all, has nothing left on the wire.
     */
    fun afterEnd(): AfterEnd =
        when (wire) {
            is Wire.Sending, is Wire.Answering, is Wire.Silent -> AfterEnd.Lingers
            is Wire.Quiet, is Wire.Flushing, is Wire.Draining, is Wire.Done -> {
                done()
                AfterEnd.Released
            }
        }

    /** Nothing more goes out: the copy is freed. */
    fun done() {
        when (val w = wire) {
            is Wire.Answering -> w.copy.free()
            is Wire.Quiet, is Wire.Flushing, is Wire.Sending, is Wire.Draining, is Wire.Silent, is Wire.Done -> Unit
        }
        wire = Wire.Done
    }
}
