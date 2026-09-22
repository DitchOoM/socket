package com.ditchoom.socket.testkit.echo

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What one bounded write did. */
public sealed interface EchoWrite {
    public data object Written : EchoWrite

    /** The deadline passed before the stream took the bytes. */
    public data object TimedOut : EchoWrite
}

/** The stream an [EchoLoop] exchanges over: a bounded write and a bounded read, nothing else. */
public interface EchoStream {
    public suspend fun write(
        payload: String,
        deadline: Duration,
    ): EchoWrite

    /** The next chunk the stream delivers, or [StreamReply.StillOwed] once [deadline] passes without one. */
    public suspend fun read(deadline: Duration): StreamReply
}

/** What a failed exchange means for the connection under it. */
public sealed interface EchoFailure {
    /** The exchange failed; the connection may still carry the next one. */
    public data object Exchange : EchoFailure

    /** The connection closed under the stream; [reason] is the transport's own account of the close. */
    public data class ConnectionClosed(
        val reason: String,
    ) : EchoFailure
}

/** What the loop observed, for a probe's status display and silence watchdog. The log lines are the loop's own. */
public sealed interface EchoLoopEvent {
    /** Exchange [seq] is about to be written. */
    public data class Round(
        val seq: Int,
    ) : EchoLoopEvent

    public data class Read(
        val read: EchoRead.Consumed,
    ) : EchoLoopEvent

    /** The first divergence on this stream; later ones are logged only. */
    public data class IntegrityBroken(
        val atByte: Int,
    ) : EchoLoopEvent

    /** The session's [EchoSession.exchanges] after an exchange completed. */
    public data class Progress(
        val exchanges: Int,
    ) : EchoLoopEvent

    public data class WriteTimedOut(
        val owedBytes: Int,
    ) : EchoLoopEvent

    public data class Overdue(
        val overdue: EchoOverdue,
    ) : EchoLoopEvent

    /** An exchange threw; [owedBytes] is what the stream still owes. */
    public data class Failed(
        val owedBytes: Int,
    ) : EchoLoopEvent
}

/** Why [EchoLoop.run] returned. */
public sealed interface EchoLoopEnd {
    /** The walk's deadline passed with the stream still open. */
    public data object WalkOver : EchoLoopEnd

    /** The session ended the stream: [step] says how. */
    public data class Left(
        val step: EchoStep.Reconnect,
    ) : EchoLoopEnd

    /** The connection closed under the stream. */
    public data class ConnectionClosed(
        val reason: String,
    ) : EchoLoopEnd
}

/**
 * The walk probes' echo loop over one connection's stream: write `probe-N;`, read the echo, let
 * [session] judge it, report, wait [interval], until the walk's deadline or the stream ends.
 *
 * Every exchange is judged by [session] when its reply arrives: everything received must be an
 * in-order prefix of everything sent, and a missed read deadline is where "late" begins, not where
 * an exchange fails. A FIN or RESET from the peer, or a run of writes it will not drain, ends the
 * session instead: the stream is no longer being echoed.
 *
 * [clock] is the time every exchange is stamped with, and [closedBy] decides whether a throw from
 * the stream means the connection is gone. Owned by one coroutine, as [session] is.
 */
public class EchoLoop(
    private val stream: EchoStream,
    private val session: EchoSession,
    private val interval: Duration,
    private val clock: () -> Duration,
    private val emit: (String) -> Unit,
    private val closedBy: (Throwable) -> EchoFailure,
    private val writeDeadline: Duration = WRITE_DEADLINE,
    private val observe: (EchoLoopEvent) -> Unit,
) {
    /** Exchange until [until] on [clock], or until the stream or the connection ends. */
    public suspend fun run(until: Duration): EchoLoopEnd {
        var seq = 0
        var integrityBroken = false
        while (clock() < until) {
            seq++
            observe(EchoLoopEvent.Round(seq))
            val sentAt = clock()
            // Delimited so payload boundaries stay visible in a coalesced read.
            val payload = "probe-$seq;"
            try {
                when (val step = exchange(seq, payload, sentAt)) {
                    is EchoStep.Exchanged -> {
                        when (step) {
                            is EchoStep.Exchanged.Judged ->
                                when (val read = step.read) {
                                    is EchoRead.Consumed -> {
                                        read.outcomes.forEach { emit(it.line(read.owedBytes)) }
                                        observe(EchoLoopEvent.Read(read))
                                    }
                                    is EchoRead.Diverged ->
                                        if (integrityBroken) {
                                            emit("ECHO-DIVERGED seq=$seq atByte=${read.atByte}")
                                        } else {
                                            integrityBroken = true
                                            observe(EchoLoopEvent.IntegrityBroken(read.atByte))
                                            emit("STREAM-INTEGRITY-BROKEN seq=$seq ${read.detail}")
                                        }
                                }
                            EchoStep.Exchanged.StillOwed -> Unit
                        }
                        // Progress is a completed exchange: a write the peer never drained is not one,
                        // so the silence watchdog sees it.
                        observe(EchoLoopEvent.Progress(session.exchanges))
                    }
                    is EchoStep.WriteTimedOut -> {
                        emit(step.line)
                        observe(EchoLoopEvent.WriteTimedOut(session.owedBytes))
                    }
                    // The peer stopped echoing this stream: leave, exactly as a dead connection does,
                    // and say which of the two it was, because they are different events.
                    is EchoStep.Reconnect -> {
                        emit(step.line)
                        return EchoLoopEnd.Left(step)
                    }
                }
            } catch (e: Throwable) {
                emit("ECHO-FAIL seq=$seq after=${(clock() - sentAt).inWholeMilliseconds}ms err=${e::class.simpleName} msg=${e.message}")
                observe(EchoLoopEvent.Failed(session.owedBytes))
                // A dead connection makes "keep going" a loop spinning against a closed connection for
                // the rest of the walk. Leave, and let the caller reconnect: a reconnect is itself
                // data, being precisely what distinguishes "migrated" from "had to start over".
                when (val failure = closedBy(e)) {
                    EchoFailure.Exchange -> Unit
                    is EchoFailure.ConnectionClosed -> {
                        session.ended(SessionEnd.ConnectionDead, clock())
                        emit("CONNECTION-DEAD seq=$seq reason=${failure.reason} — leaving scope to reconnect")
                        return EchoLoopEnd.ConnectionClosed(failure.reason)
                    }
                }
            }
            session.overdue(clock()).forEach {
                emit(it.line)
                observe(EchoLoopEvent.Overdue(it))
            }
            delay(interval)
        }
        return EchoLoopEnd.WalkOver
    }

    /** One exchange: the write, then the read the session judges. A write that times out is the session's to decide. */
    private suspend fun exchange(
        seq: Int,
        payload: String,
        sentAt: Duration,
    ): EchoStep {
        when (stream.write(payload, writeDeadline)) {
            EchoWrite.TimedOut -> return session.writeTimedOut(seq, waited = clock() - sentAt, at = clock())
            EchoWrite.Written -> session.sent(seq, payload, clock())
        }
        return session.reply(seq, stream.read(session.readDeadline), clock())
    }

    public companion object {
        /** Bounds one write; [EchoSession.WRITE_TIMEOUT_STREAK_LIMIT] of these in a row ends the session. */
        public val WRITE_DEADLINE: Duration = 5.seconds
    }
}
