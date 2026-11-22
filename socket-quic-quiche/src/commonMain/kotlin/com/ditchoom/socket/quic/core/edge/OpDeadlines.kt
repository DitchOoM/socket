@file:OptIn(ExperimentalAtomicApi::class)

package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.DeadlineFired
import com.ditchoom.socket.quic.Rearmable
import com.ditchoom.socket.quic.Wanted
import com.ditchoom.socket.quic.core.conn.CallerPlace
import com.ditchoom.socket.quic.core.conn.Conn
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.observe.Published
import com.ditchoom.socket.quic.core.time.Nanos
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/** An operation that waits parked with a bound, told inside that the bound passed while it still waited; or none. */
internal sealed interface Bounded {
    /** A slot no operation holds. */
    data object NoOperation : Bounded
}

/** An operation's hold on a deadline: none, or the [DeadlineSlot] it was lent. */
internal sealed interface Lease {
    data object None : Lease
}

/** What [FiredDue.take] found: the arming held now fell due, or what fell due was an earlier arming's. */
internal enum class Expiry { Due, Stale }

/**
 * Which arming of a slot fell due, handed from the loop to whoever is inside, in one atomic word: [Wanted.None] while
 * no expiry is queued, else the time of the latest arming that fell due. The loop queues the slot's one expiry only when
 * it moves the word off None; inside, the expiry takes the word back to None and expires the operation only when the
 * time is the one the slot is armed for now. A record of an arming since let go (the operation settled as its deadline
 * fired) is stale, so it never expires the slot's next holder.
 */
@Published
internal class FiredDue {
    private val word = AtomicLong(Wanted.None.word())

    /** The loop: the arming for [due] fell due; true when the expiry must be queued, false when it is queued already. */
    fun fell(due: Long): Boolean = word.exchange(due) == Wanted.None.word()

    /** Inside, as the expiry runs: takes the record against what the slot is [armed] for. */
    fun take(armed: Wanted): Expiry {
        val fired = Wanted.ofWord(word.exchange(Wanted.None.word()))
        return if (fired != Wanted.None && fired == armed) Expiry.Due else Expiry.Stale
    }

    /** The connection ended before the expiry could run: a later fire may queue it again. */
    fun dropped() = word.store(Wanted.None.word())
}

/**
 * One reusable operation deadline of a connection: a [Rearmable] and the one expiry work it hands the connection, made
 * once and lent to each operation that parks with a bound. Inside only, except [fired] (the loop).
 */
@Confined
internal class DeadlineSlot(
    private val conn: Conn,
    inside: Inside,
) : ConnWork(),
    DeadlineFired,
    Lease {
    private val deadline: Rearmable = inside.rearmable(this)
    private val record = FiredDue()
    private var holder: Bounded = Bounded.NoOperation
    private var armed: Wanted = Wanted.None

    /** Lends the slot to [operation], armed for [due]. */
    fun hold(
        operation: Bounded,
        due: Nanos,
        inside: Inside,
    ) {
        holder = operation
        armed = Wanted.at(due.value)
        inside.arm(deadline, due)
    }

    /** Takes the slot back: disarmed, held by nobody; an expiry still queued finds it stale. */
    fun letGo() {
        deadline.disarm()
        holder = Bounded.NoOperation
        armed = Wanted.None
    }

    /** The loop: an arming fell due; the connection expires the holder from inside. */
    override fun fired(due: Long) {
        if (record.fell(due)) conn.submit(this, CallerPlace.HandToLoop)
    }

    override fun run(inside: Inside) {
        when (record.take(armed)) {
            Expiry.Due -> expire(holder, inside)
            Expiry.Stale -> Unit
        }
    }

    override fun gone(end: ConnEnd) = record.dropped()

    private fun expire(
        operation: Bounded,
        inside: Inside,
    ) = when (operation) {
        is ReadOp -> operation.expired(inside)
        is WriteOp -> operation.expired(inside)
        is StreamTransfer -> operation.expired(inside)
        is OpenOp -> operation.expired(inside)
        is Bounded.NoOperation -> Unit
    }
}

/**
 * A connection's operation deadlines: slots lent to operations that park with a finite bound and taken back when they
 * settle, so parking with a bound allocates nothing once the connection has as many slots as it ever had parked
 * operations. Inside only.
 */
@Confined
internal class OpDeadlines(
    private val conn: Conn,
) {
    private val spare = ArrayList<DeadlineSlot>()

    /** Arms a deadline for [operation], [bound] from now: a lent slot, or none when the bound is infinite. */
    fun arm(
        operation: Bounded,
        bound: Duration,
        inside: Inside,
    ): Lease {
        if (!bound.isFinite()) return Lease.None
        val slot = if (spare.isEmpty()) DeadlineSlot(conn, inside) else spare.removeAt(spare.size - 1)
        slot.hold(operation, Nanos(inside.now().value + bound.inWholeNanoseconds), inside)
        return slot
    }

    /** Takes back what [lease] holds; returns [Lease.None], what the operation holds from here. */
    fun disarm(lease: Lease): Lease {
        when (lease) {
            is Lease.None -> Unit
            is DeadlineSlot -> {
                lease.letGo()
                spare.add(lease)
            }
        }
        return Lease.None
    }
}
