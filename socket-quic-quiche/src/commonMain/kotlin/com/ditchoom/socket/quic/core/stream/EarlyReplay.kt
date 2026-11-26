package com.ditchoom.socket.quic.core.stream

import com.ditchoom.socket.quic.StreamSendStep
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.pool.LongSet
import kotlinx.coroutines.Runnable

/** Where a client's [StreamSend] also hands each send quiche took before its server settled 0-RTT. */
internal sealed interface EarlyCopy {
    data object Off : EarlyCopy

    /** [keep] is handed the stream, the address and length of the bytes quiche took, and whether they ended the stream. */
    class To(
        val keep: (stream: Long, address: Long, length: Int, end: WriteEnd) -> Unit,
    ) : EarlyCopy
}

/** When a replay is handed to quiche: at once, or once the server passed the client's check of it. */
internal enum class ReplayStart { Now, AfterCheck }

/** Whether a stream's replayed bytes are all in quiche, so its caller's sends may follow. */
internal enum class Replaying { Clear, Behind }

/** Whether a replayed send is all in quiche. */
private enum class Handed { Partly, Whole }

/** A kept send replayed after a rejected 0-RTT: [length] bytes at [address] on [stream], ending it as [end]. */
@Confined
internal class Replayed(
    val stream: Long,
    val address: Long,
    val length: Int,
    val end: WriteEnd,
) {
    private var sent = 0
    private var handed = Handed.Partly

    fun over(): Boolean = handed == Handed.Whole

    /** Hands quiche what it has not taken yet through [send]; [Replaying.Behind] while some is left. */
    fun push(send: (Long, Long, Int, WriteEnd) -> StreamSendStep): Replaying {
        if (handed == Handed.Whole) return Replaying.Clear
        val step = send(stream, address + sent, length - sent, end)
        return step.fold(
            took = { took ->
                sent += took
                if (sent == length) handed = Handed.Whole
                if (handed == Handed.Whole) Replaying.Clear else Replaying.Behind
            },
            full = { Replaying.Behind },
            // The peer stopped or reset the stream, or quiche refuses it: the caller's next send meets the same answer.
            refused = { _, _ ->
                handed = Handed.Whole
                Replaying.Clear
            },
        )
    }

    /** Given up with the stream: nothing more of it goes. */
    fun drop() {
        handed = Handed.Whole
    }
}

/** Every stream with replayed bytes quiche has not taken whole, and what runs once none is left. */
private sealed interface Replay {
    data object Idle : Replay

    class Running(
        val pieces: ArrayList<Replayed>,
        val done: Runnable,
    ) : Replay
}

/**
 * The replay of a client's sends after its server rejected 0-RTT, in the order they were made, ahead of whatever its
 * callers send on the same streams after: such a send waits ([holds]) until the stream's replayed bytes are all in
 * quiche; a FIN alone follows them ([finAfter]).
 */
@Confined
internal class EarlyReplay {
    private var replay: Replay = Replay.Idle
    private var start = ReplayStart.Now
    private val behind = LongSet()

    fun holds(stream: Long): Boolean = stream in behind

    /** Holds [pieces]' streams and hands quiche the pieces as [start] says. */
    fun start(
        pieces: List<Replayed>,
        done: Runnable,
        start: ReplayStart,
        send: (Long, Long, Int, WriteEnd) -> StreamSendStep,
    ) {
        replay = Replay.Running(ArrayList(pieces), done)
        this.start = start
        for (piece in pieces) behind.add(piece.stream)
        when (start) {
            ReplayStart.Now -> release(send)
            ReplayStart.AfterCheck -> Unit
        }
    }

    /** The replay may go: every held stream hands quiche what it can now. */
    fun release(send: (Long, Long, Int, WriteEnd) -> StreamSendStep) {
        start = ReplayStart.Now
        val running =
            when (val now = replay) {
                is Replay.Running -> now
                is Replay.Idle -> return
            }
        for (piece in ArrayList(running.pieces)) if (holds(piece.stream)) resume(piece.stream, send)
        finishIfDone()
    }

    /** Hands quiche what [stream] still has to replay. */
    fun resume(
        stream: Long,
        send: (Long, Long, Int, WriteEnd) -> StreamSendStep,
    ): Replaying {
        if (!holds(stream)) return Replaying.Clear
        if (start == ReplayStart.AfterCheck) return Replaying.Behind
        val running =
            when (val now = replay) {
                is Replay.Running -> now
                is Replay.Idle -> return Replaying.Clear
            }
        for (piece in running.pieces) {
            if (piece.stream != stream || piece.over()) continue
            if (piece.push(send) == Replaying.Behind) return Replaying.Behind
        }
        behind.remove(stream)
        finishIfDone()
        return Replaying.Clear
    }

    /** A FIN alone on a stream still replaying goes after its bytes. */
    fun finAfter(stream: Long) {
        when (val now = replay) {
            is Replay.Running -> now.pieces.add(Replayed(stream, 0L, 0, WriteEnd.Finish))
            is Replay.Idle -> Unit
        }
    }

    /** The connection is past its close point: nothing more is replayed, and the memory is its owner's to free. */
    fun drop() {
        when (val now = replay) {
            is Replay.Running -> now.pieces.forEach { it.drop() }
            is Replay.Idle -> Unit
        }
        behind.clear()
        start = ReplayStart.Now
        replay = Replay.Idle
    }

    private fun finishIfDone() {
        val running =
            when (val now = replay) {
                is Replay.Running -> now
                is Replay.Idle -> return
            }
        if (behind.isNotEmpty()) return
        replay = Replay.Idle
        running.done.run()
    }
}
