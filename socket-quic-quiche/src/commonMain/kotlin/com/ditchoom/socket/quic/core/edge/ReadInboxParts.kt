package com.ditchoom.socket.quic.core.edge

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.socket.quic.ScopedRead
import kotlinx.coroutines.CancellableContinuation
import kotlin.time.Duration

/** What a read of a [ReadInbox] is answered. */
internal sealed interface ReadNext {
    /** The stream's buffer, lent until the read's block returns; one per stream, made once. */
    class Lent(
        val bytes: ReadBuffer,
    ) : ReadNext

    /** After the connection's end: bytes it held for the stream, the caller's to free once its block returns. */
    class Held(
        val bytes: PlatformBuffer,
    ) : ReadNext

    /** The stream gives no more. */
    class Ended(
        val end: ScopedRead.End,
    ) : ReadNext

    data object DeadlinePassed : ReadNext

    /** The stream reads no more: it was stopped, or a receiver took it. */
    data object Closed : ReadNext

    /** Another read of the stream is still out: a stream has one reader at a time. */
    data object Busy : ReadNext

    /** quiche refused the read, or the read's own attempt threw. */
    class Refused(
        val failure: Raised,
    ) : ReadNext
}

/** Who has a [ReadInbox]'s buffer, and whether a read waits. */
internal sealed interface ReadHold {
    /** The loop has it; no read waits. */
    data object Idle : ReadHold

    /** The loop has it; [caller] waits for bytes, at most [bound]. */
    class Waiting(
        val caller: CancellableContinuation<ReadNext>,
        val bound: Duration,
    ) : ReadHold

    /** A read's block has it. */
    data object Lent : ReadHold

    /** The stream let it go; the loop frees it. */
    data object Leaving : ReadHold

    /** The stream let it go while a block had it; the block's return hands it to the loop to free. */
    data object LeavingLent : ReadHold

    /** Freed: the connection ended or the stream let go. Reads take what the end held. */
    data object Left : ReadHold

    /** A read's block has it, and the stream gave its end: the block's return frees it, and it is [spent]. */
    class SpentLent(
        val spent: Spent,
    ) : ReadHold

    /** Freed: the stream gave its end, which every read after answers. */
    class Spent(
        val answer: ReadNext,
    ) : ReadHold
}

/** Whether a read parked, or was answered without waiting. */
internal sealed interface ReadPark {
    data object Parked : ReadPark

    class Answered(
        val next: ReadNext,
    ) : ReadPark
}

/** Whether a read came back with its answer, or its caller was cancelled out of the wait. */
internal enum class ReadReturn { Answered, Cancelled }

/** Inside: whether the loop met the stream's end, and how it answers reads. */
internal sealed interface ReadEnd {
    data object Open : ReadEnd

    /** The end's states of the buffer, made with it, so a stream ending allocates nothing more. */
    class Ended(
        val answer: ReadNext,
    ) : ReadEnd {
        val spent = ReadHold.Spent(answer)
        val spentLent = ReadHold.SpentLent(spent)
    }
}

/** A held buffer that a cancelled caller never took is freed. */
internal fun ReadNext.freeUntaken() =
    when (this) {
        is ReadNext.Held -> bytes.freeIfNeeded()
        is ReadNext.Lent, is ReadNext.Ended, is ReadNext.DeadlinePassed, is ReadNext.Closed, is ReadNext.Busy,
        is ReadNext.Refused,
        -> Unit
    }
