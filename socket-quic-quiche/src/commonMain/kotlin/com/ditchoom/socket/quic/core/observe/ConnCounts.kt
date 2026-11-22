package com.ditchoom.socket.quic.core.observe

import com.ditchoom.socket.quic.QuicDatagramCount
import com.ditchoom.socket.quic.QuicStatsRead

/** What a connection did with the threads and datagrams that reached it: any thread counts, tests read. */
internal class ConnCounts {
    private val entered = Counter()
    private val busy = Counter()
    private val handed = Counter()
    private val copied = Counter()
    private val dropped = Counter()
    private val late = Counter()
    private val resent = Counter()
    private val overflowed = Counter()
    private val refused = Counter()
    private val mismatched = Counter()
    private val evicted = Counter()
    private val expiredOut = Counter()
    private val unsendable = Counter()
    private val expiredIn = Counter()

    /** Times a caller entered a free connection and served it on its own thread. */
    val enteredInPlace: Long get() = entered.value()

    /** Times a caller left work for the thread already inside. */
    val leftForHolder: Long get() = busy.value()

    /** Times work reached a free connection and the loop was asked to serve it. */
    val handedToLoop: Long get() = handed.value()

    /** Datagrams that arrived while a caller was inside and were copied into the connection's work. */
    val datagramsCopied: Long get() = copied.value()

    /** Datagrams that arrived after the connection stopped taking work, or found no memory to be copied into. */
    val datagramsDropped: Long get() = dropped.value()

    /** Datagrams that arrived after the connection ended: none is handed to quiche. */
    val lateDatagrams: Long get() = late.value()

    /** Times a lingering connection answered a late datagram with its CONNECTION_CLOSE again. */
    val closesResent: Long get() = resent.value()

    /** Received RFC 9221 datagrams dropped because the connection's receive queue was full and nobody was receiving. */
    val datagramsOverflowed: Long get() = overflowed.value()

    /** RFC 9221 datagrams the connection's send queue refused because it was full: the caller was told, nothing was queued. */
    val datagramsRefusedQueueFull: Long get() = refused.value()

    /** Queued RFC 9221 datagrams dropped, oldest first, to make room for a newer send. */
    val datagramsEvicted: Long get() = evicted.value()

    /** Queued RFC 9221 datagrams dropped unsent because they waited longer than the outgoing maximum age. */
    val datagramsExpiredOutgoing: Long get() = expiredOut.value()

    /** Queued RFC 9221 datagrams quiche would not take when they reached it, the path having shrunk below their size. */
    val datagramsDroppedUnsendable: Long get() = unsendable.value()

    /** Received RFC 9221 datagrams dropped undelivered because they waited longer than the incoming maximum age. */
    val datagramsExpiredIncoming: Long get() = expiredIn.value()

    /** Datagrams whose due time quiche stamped on a clock the loop does not read: each was sent at once, unpaced. */
    val pacingClockMismatches: Long get() = mismatched.value()

    fun enteredInPlace() = entered.increment()

    fun leftForHolder() = busy.increment()

    fun handedToLoop() = handed.increment()

    fun datagramCopied() = copied.increment()

    fun datagramDropped() = dropped.increment()

    fun lateDatagram() = late.increment()

    fun closeResent() = resent.increment()

    fun datagramOverflowed() = overflowed.increment()

    fun datagramRefusedQueueFull() = refused.increment()

    fun pacingClockMismatch() = mismatched.increment()

    fun datagramEvicted() = evicted.increment()

    fun datagramExpiredOutgoing() = expiredOut.increment()

    fun datagramDroppedUnsendable() = unsendable.increment()

    fun datagramExpiredIncoming() = expiredIn.increment()

    /** Every count, read now. */
    fun seen(): CountsSeen =
        CountsSeen(
            enteredInPlace = enteredInPlace,
            leftForHolder = leftForHolder,
            handedToLoop = handedToLoop,
            datagramsCopied = datagramsCopied,
            datagramsDropped = datagramsDropped,
            lateDatagrams = lateDatagrams,
            pacingClockMismatches = pacingClockMismatches,
        )
}

/** A connection's counts as one read saw them; see [ConnCounts] for what each counts. */
internal class CountsSeen(
    val enteredInPlace: Long,
    val leftForHolder: Long,
    val handedToLoop: Long,
    val datagramsCopied: Long,
    val datagramsDropped: Long,
    val lateDatagrams: Long,
    val pacingClockMismatches: Long,
)

/** This read with the datagrams [counts] saw refused at the sender and dropped at the receiver; a read that has no statistics has none. */
internal fun QuicStatsRead.withDatagramCounts(counts: ConnCounts): QuicStatsRead =
    when (this) {
        is QuicStatsRead.Read ->
            QuicStatsRead.Read(
                stats.copy(
                    datagramsRefusedQueueFull = QuicDatagramCount.Counted(counts.datagramsRefusedQueueFull),
                    datagramsDroppedReceiveQueue = QuicDatagramCount.Counted(counts.datagramsOverflowed),
                    datagramsEvicted = QuicDatagramCount.Counted(counts.datagramsEvicted),
                    datagramsExpiredOutgoing = QuicDatagramCount.Counted(counts.datagramsExpiredOutgoing),
                    datagramsDroppedUnsendable = QuicDatagramCount.Counted(counts.datagramsDroppedUnsendable),
                    datagramsExpiredIncoming = QuicDatagramCount.Counted(counts.datagramsExpiredIncoming),
                ),
            )
        is QuicStatsRead.Ended, is QuicStatsRead.NotReported -> this
    }
