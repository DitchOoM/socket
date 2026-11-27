@file:Suppress("ktlint:standard:filename") // rule Q names the deciding file path/Paths*: a client's path silence is judged here.

package com.ditchoom.socket.quic.core.path

import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.Span
import com.ditchoom.socket.quic.core.time.WakeSink

/** Whether a serve sent the awaiting-peer probe. */
internal enum class ProbeStep { Rested, Pinged }

/** The news a connection has not recorded in its flight yet. */
internal enum class Unrecorded { None, Silent, Answering }

private enum class WatchPhase { Handshaking, Established }

/** The last comparable read of the active path. [Baselined] is the watch's own, filled in place. */
private sealed interface Progress {
    data object Unread : Progress

    class Baselined : Progress {
        var index = 0L
        var probes = 0L
    }
}

/** The run of unanswered PTO expiries on the active path. [Building] and [Declared] are the watch's own, filled in place. */
private sealed interface Run {
    data object None : Run

    class Building : Run {
        var expiries = 0L
        var since = 0L
    }

    class Declared : Run {
        var since = 0L
    }
}

/** Whether the path owes an answer to ack-eliciting data handed to quiche since it last answered. */
private sealed interface Owed {
    data object None : Owed

    class Owing : Owed {
        var at = 0L
    }
}

/**
 * A client's watch on its active path, inside the connection. With [Silence.On] it samples quiche's PTO expiries of the
 * active path (found by its flag, never by index 0) when quiche's timer fires, resets the run on every arrival, and
 * declares the path [PathNews.Silent] once [SilenceThreshold] is met: at the expiry that meets it, at the run's floor,
 * or at the patience of an owed answer. The first read after establishment is the baseline; a read of another path or
 * of a counter that went back is a baseline again.
 *
 * Whatever [Silence], an established client with a read waiting, nothing ack-eliciting unanswered and nothing arrived
 * for the floor sends one PING (the awaiting-peer probe): a peer whose flight a NAT rebinding lost then learns the new
 * address. Each probe doubles the next wait, up to the probe cap; data handed to a reader resets it. Every state is the
 * watch's own object, so a sample, an arrival or a wake allocates nothing.
 */
@Confined
internal class PathSilence(
    private val q: QuicheConnRef,
    rule: SilenceRule,
    private val threshold: SilenceThreshold = SilenceThreshold.SHIPPED,
) {
    private val watch = rule.watch
    private val cap = rule.probeCap.nanos
    private val cell = rule.news
    private val read = Progress.Baselined()
    private var progress: Progress = Progress.Unread
    private val building = Run.Building()
    private val declared = Run.Declared()
    private var run: Run = Run.None
    private val owing = Owed.Owing()
    private var owed: Owed = Owed.None
    private var phase = WatchPhase.Handshaking
    private var handedSeen = 0L
    private var handedUpSeen = 0L
    private var quietSince = 0L
    private var interval = threshold.floor.nanos
    private var news: PathNews = PathNews.Answering
    private var recorded: PathNews = PathNews.Answering

    /** The handshake completed, so the path is known good: the baseline is read. */
    fun baseline() {
        phase = WatchPhase.Established
        owed = Owed.None
        when (watch) {
            Silence.Off -> Unit
            Silence.On -> {
                run = Run.None
                ActiveProbes(q.api.connActivePathProbes(q.handle)).fold(none = { progress = Progress.Unread }, read = { index, probes ->
                    rebase(index, probes)
                })
            }
        }
    }

    /**
     * The connection migrated at [now]: the new path is judged afresh. A run, an owed answer and the probe's backoff of
     * the old path end, and the edge is told [PathNews.Answering] in this pass.
     */
    fun migrated(now: Nanos) {
        quietSince = now.value
        owed = Owed.None
        interval = threshold.floor.nanos
        if (watch == Silence.Off) return
        val was = run
        run = Run.None
        progress = Progress.Unread
        ActiveProbes(q.api.connActivePathProbes(q.handle)).fold(none = { Unit }, read = { index, probes -> rebase(index, probes) })
        if (was !is Run.None) tell(PathNews.Answering)
    }

    /** What the watch says of the active path now. */
    fun now(): PathNews = news

    /** A datagram arrived at [now]: the path answered. */
    fun answered(now: Nanos) {
        quietSince = now.value
        owed = Owed.None
        if (run is Run.None) return
        run = Run.None
        tell(PathNews.Answering)
    }

    /** By [now], [count] ack-eliciting hand-offs (stream data, datagrams, PINGs) were made in all. */
    fun handed(
        now: Nanos,
        count: Long,
    ) {
        if (count == handedSeen) return
        handedSeen = count
        owe(now)
    }

    /** quiche's timer fired at [now]: the active path's expiries are read and the run grows, or is declared. */
    fun sample(now: Nanos) {
        if (watch == Silence.Off) return
        ActiveProbes(q.api.connActivePathProbes(q.handle)).fold(none = { Unit }, read = { index, probes -> fold(now, index, probes) })
    }

    /** At [now], a run past the count is declared at its floor, and an answer owed past the patience is declared. */
    fun verdict(now: Nanos) {
        if (watch == Silence.Off) return
        when (val r = run) {
            is Run.Declared -> return
            is Run.Building ->
                if (r.expiries >= threshold.expiries && threshold.isMetBy(r.expiries, Span(now.value - r.since))) return declare(r.since)
            is Run.None -> Unit
        }
        if (progress !is Progress.Baselined) return
        val o = owed
        if (o is Owed.Owing && threshold.isMetByUnansweredSends(Span(now.value - o.at))) declare(o.at)
    }

    /**
     * The awaiting-peer probe at [now], with [readers] reads waiting and [handedUp] arrivals handed up in all: one PING
     * once the wait passed, the next wait doubled.
     */
    fun probe(
        now: Nanos,
        readers: Int,
        handedUp: Long,
    ): ProbeStep {
        if (handedUp != handedUpSeen) {
            handedUpSeen = handedUp
            interval = threshold.floor.nanos
        }
        val due = probeDue(readers, never = { return ProbeStep.Rested }, at = { it })
        if (now.value < due) return ProbeStep.Rested
        q.api.connSendAckEliciting(q.handle)
        quietSince = now.value
        interval = minOf(interval * 2, cap)
        owe(now)
        return ProbeStep.Pinged
    }

    /** Offers the times the probe, the run's floor and an owed answer's patience fall due. */
    fun offerWake(
        sink: WakeSink,
        readers: Int,
    ) {
        probeDue(readers, never = { Unit }, at = { sink.offer(Nanos(it)) })
        if (watch == Silence.Off) return
        val r = run
        if (r is Run.Declared) return
        if (r is Run.Building && r.expiries >= threshold.expiries) sink.offer(Nanos(r.since) + threshold.floor.nanos)
        val o = owed
        if (o is Owed.Owing && progress is Progress.Baselined) sink.offer(Nanos(o.at) + threshold.patience.nanos)
    }

    /** The news since it was last asked, taken once. */
    fun unrecorded(): Unrecorded {
        val now = news
        if (now === recorded) return Unrecorded.None
        recorded = now
        return when (now) {
            is PathNews.Silent -> Unrecorded.Silent
            is PathNews.Answering -> Unrecorded.Answering
        }
    }

    /**
     * When the awaiting-peer probe falls due with [readers] reads waiting: [never] unless the connection is established,
     * a read waits and nothing ack-eliciting is unanswered. [probe] and [offerWake] both ask this, so a wake is never
     * offered for a probe that would not be sent.
     */
    private inline fun <R> probeDue(
        readers: Int,
        never: () -> R,
        at: (Long) -> R,
    ): R =
        if (phase == WatchPhase.Established && readers > 0 && owed is Owed.None) {
            at((Nanos(quietSince) + interval).value)
        } else {
            never()
        }

    private fun owe(now: Nanos) {
        if (phase == WatchPhase.Handshaking || owed is Owed.Owing) return
        owing.at = now.value
        owed = owing
    }

    /** One read of the active path: a run grows by the expiries since the last read of the same path, and is judged. */
    private fun fold(
        now: Nanos,
        index: Long,
        probes: Long,
    ) {
        val comparable =
            when (val was = progress) {
                is Progress.Unread -> false
                is Progress.Baselined -> was.index == index && probes >= was.probes
            }
        if (!comparable) {
            owed = Owed.None
            return rebase(index, probes)
        }
        val expired = probes - read.probes
        read.probes = probes
        if (expired == 0L) return
        when (val r = run) {
            is Run.None -> {
                building.expiries = expired
                building.since = now.value
                run = building
            }
            is Run.Building -> r.expiries += expired
            is Run.Declared -> return
        }
        if (threshold.isMetBy(building.expiries, Span(now.value - building.since))) declare(building.since)
    }

    /** The path at [index], with [probes] expiries so far, is the one judged from here: no run. */
    private fun rebase(
        index: Long,
        probes: Long,
    ) {
        read.index = index
        read.probes = probes
        progress = read
        if (run is Run.None) return
        run = Run.None
        tell(PathNews.Answering)
    }

    private fun declare(at: Long) {
        declared.since = at
        run = declared
        tell(PathNews.Silent)
    }

    private fun tell(now: PathNews) {
        news = now
        cell.tell(now)
    }
}
