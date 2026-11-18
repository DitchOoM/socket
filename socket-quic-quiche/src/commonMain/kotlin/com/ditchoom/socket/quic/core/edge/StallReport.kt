package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.QuicStatsRead
import com.ditchoom.socket.quic.core.conn.ConnWork
import com.ditchoom.socket.quic.core.conn.Inside
import com.ditchoom.socket.quic.core.conn.StallView
import com.ditchoom.socket.quic.core.life.ConnEnd
import com.ditchoom.socket.quic.core.life.Life
import com.ditchoom.socket.quic.core.observe.CountsSeen
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * Why a connection is not making progress, for a caller that stopped hearing from it: what any thread can read
 * without entering ([life], [counts]) and what the thread inside saw ([inside]). Diagnostics only.
 */
internal class StallReport(
    val life: Life,
    val counts: CountsSeen,
    val inside: InsideAnswer,
)

/** What the connection answered when asked from inside. */
internal sealed interface InsideAnswer {
    class Answered(
        val view: StallView,
    ) : InsideAnswer

    /** Nobody inside served the request within [waited]: a thread inside holds the connection, or its loop stopped serving. */
    class NoAnswer(
        val waited: Duration,
    ) : InsideAnswer

    class Ended(
        val end: ConnEnd,
    ) : InsideAnswer
}

/** Reads the stall view inside and completes [answer]; its caller races it against a bound. */
internal class StallWork(
    private val answer: CompletableDeferred<InsideAnswer>,
) : ConnWork() {
    override fun run(inside: Inside) {
        answer.complete(InsideAnswer.Answered(inside.stallView()))
    }

    override fun gone(end: ConnEnd) {
        answer.complete(InsideAnswer.Ended(end))
    }
}

/** Reads the connection's statistics inside and resumes the caller with them. */
internal class StatsWork(
    private val cont: CancellableContinuation<QuicStatsRead>,
) : ConnWork() {
    override fun run(inside: Inside) = cont.resume(inside.stats())

    override fun gone(end: ConnEnd) = cont.resume(QuicStatsRead.Ended)
}
