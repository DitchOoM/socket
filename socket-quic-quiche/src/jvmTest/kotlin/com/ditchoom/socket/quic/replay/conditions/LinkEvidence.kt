package com.ditchoom.socket.quic.replay.conditions

import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A datagram as a recording holds it, on the script's clock. */
internal class Seen(
    val at: Duration,
    val hex: String,
)

/** What the other end's recording adds: its datagrams on our clock. */
internal sealed interface PeerSide {
    data object Unrecorded : PeerSide

    class Recorded(
        val received: List<Seen>,
        val sent: List<Seen>,
    ) : PeerSide
}

/**
 * The link's windows, derived from the datagrams one or both ends recorded. A datagram of ours sent on its own (not
 * an answer to one that arrived just before) that no arrival follows within [ANSWER_WITHIN] went unanswered; a run of
 * them lasting [MIN_OUTAGE] or more is an outage, until the next arrival, or until the OS brings a network back up
 * after the outage began: from then on the silence is the socket's (Android's old socket on a new network), not the
 * link's. Datagrams sent and received while the endpoint had moved away from the bound address are no evidence about
 * its path, so they are left out; an outage under way when it moved continues through. Between outages and OS events each window carries the delay from its round trips and the loss from its
 * unanswered datagrams, split evenly over the two directions, or counted per direction when the peer's recording
 * says which datagrams it never received.
 */
internal object LinkEvidence {
    val ANSWER_WITHIN = 1.seconds
    val MIN_OUTAGE = 1.seconds
    private val ANSWERS_WITHIN = 25.milliseconds
    private val FALLBACK_DELAY = DelayRange(25.milliseconds, 25.milliseconds)

    fun windows(
        sent: List<Seen>,
        received: List<Seen>,
        peer: PeerSide,
        start: NetHandle,
        os: List<OsEvent>,
        runFor: Duration,
        away: List<ClosedRange<Duration>> = emptyList(),
    ): List<LinkWindow> {
        val arrivals = received.map { it.at }.filter { at -> away.none { at in it } }.sorted()
        val own =
            sent
                .filter { out -> away.none { out.at in it } && arrivals.none { it <= out.at && out.at - it <= ANSWERS_WITHIN } }
                .sortedBy { it.at }
        val outages = outages(own, arrivals, os, runFor, away.map { it.start })
        val deaf = deafSpans(start, os, arrivals, runFor)
        val heard = own.filter { out -> deaf.none { out.at >= it.start && out.at < it.endExclusive } }
        val cuts =
            (outages.flatMap { listOf(it.from, it.until) } + os.map { it.at } + listOf(Duration.ZERO, runFor))
                .filter { it in Duration.ZERO..runFor }
                .distinct()
                .sorted()
        val windows = ArrayList<LinkWindow>()
        for (i in 0 until cuts.size - 1) {
            val from = cuts[i]
            val until = cuts[i + 1]
            if (until <= from) continue
            windows +=
                if (outages.any { from >= it.from && until <= it.until }) {
                    LinkWindow.Outage(from, until)
                } else {
                    flowing(from, until, heard, arrivals, sent, received, peer)
                }
        }
        return merged(fillDelays(windows))
    }

    private fun outages(
        own: List<Seen>,
        arrivals: List<Duration>,
        os: List<OsEvent>,
        runFor: Duration,
        leaves: List<Duration>,
    ): List<LinkWindow.Outage> {
        val found = ArrayList<LinkWindow.Outage>()
        for (out in own) {
            if (found.any { out.at >= it.from && out.at < it.until }) continue
            if (answer(out.at, arrivals) is Answer.Within) continue
            // Its answer came on the path the endpoint moved to: no evidence the bound path went quiet.
            if (leaves.any { it > out.at && it - out.at <= ANSWER_WITHIN }) continue
            val lastIn = (arrivals.filter { it <= out.at } + Duration.ZERO).max()
            val nextIn = (arrivals.filter { it > out.at } + runFor).min()
            val offline = os.filterIsInstance<OsEvent.Offline>().filter { it.at in lastIn..nextIn }.map { it.at }
            val from = (offline + out.at).min()
            // Back up after going offline: what stays silent from then on is the socket, not the link.
            val backUp = os.filterIsInstance<OsEvent.Up>().filter { offline.isNotEmpty() && it.at > from && it.at < nextIn }.map { it.at }
            val until = (backUp + nextIn).min()
            if (until - from >= MIN_OUTAGE) found += LinkWindow.Outage(from, until)
        }
        return found
    }

    /**
     * From each network the OS brings up in place of the one before (or after none) to the next arrival: what goes
     * unanswered then is a socket still tied to the old network, which the replay models itself, not the link's loss.
     */
    private fun deafSpans(
        start: NetHandle,
        os: List<OsEvent>,
        arrivals: List<Duration>,
        runFor: Duration,
    ): List<OpenEndRange<Duration>> {
        val spans = ArrayList<OpenEndRange<Duration>>()
        var on: OsNow = OsNow.On(start)
        for (event in os) {
            when (event) {
                is OsEvent.Offline -> on = OsNow.Offline
                is OsEvent.Up -> {
                    if (on != OsNow.On(event.up.network)) {
                        spans += event.at..<(arrivals.filter { it > event.at } + runFor).min()
                    }
                    on = OsNow.On(event.up.network)
                }
                is OsEvent.Route -> Unit
            }
        }
        return spans
    }

    private sealed interface Answer {
        class Within(
            val after: Duration,
        ) : Answer

        data object None : Answer
    }

    private fun answer(
        at: Duration,
        arrivals: List<Duration>,
    ): Answer {
        val next = arrivals.filter { it > at && it - at <= ANSWER_WITHIN }
        return if (next.isEmpty()) Answer.None else Answer.Within(next.min() - at)
    }

    private fun flowing(
        from: Duration,
        until: Duration,
        own: List<Seen>,
        arrivals: List<Duration>,
        sent: List<Seen>,
        received: List<Seen>,
        peer: PeerSide,
    ): LinkWindow.Flowing {
        val inside = own.filter { it.at >= from && it.at < until }
        val rtts =
            inside
                .map { answer(it.at, arrivals) }
                .filterIsInstance<Answer.Within>()
                .map { it.after }
                .sorted()
        val delay =
            if (rtts.isEmpty()) {
                UNKNOWN_DELAY
            } else {
                DelayRange(rtts[rtts.size / 10] / 2, rtts[(rtts.size * 9 / 10).coerceAtMost(rtts.size - 1)] / 2)
            }
        return when (peer) {
            is PeerSide.Unrecorded -> {
                val unanswered = inside.count { answer(it.at, arrivals) is Answer.None }
                val each = perDirection(unanswered, inside.size)
                LinkWindow.Flowing(from, until, delay, each, each)
            }
            is PeerSide.Recorded -> {
                val ours = sent.filter { it.at >= from && it.at < until }
                val theirs = peer.sent.filter { it.at >= from && it.at < until }
                LinkWindow.Flowing(from, until, delay, lost(ours, peer.received), lost(theirs, received))
            }
        }
    }

    private fun lost(
        sent: List<Seen>,
        received: List<Seen>,
    ): PerMille {
        if (sent.isEmpty()) return PerMille(0)
        val waiting = received.groupingBy { it.hex }.eachCount().toMutableMap()
        var lost = 0
        for (datagram in sent) {
            val left = waiting.getOrElse(datagram.hex) { 0 }
            if (left == 0) lost++ else waiting[datagram.hex] = left - 1
        }
        return PerMille(lost * PER_MILLE / sent.size)
    }

    /** A round-trip loss of [unanswered] in [total], as the loss of each direction when both lose alike. */
    private fun perDirection(
        unanswered: Int,
        total: Int,
    ): PerMille {
        if (total == 0) return PerMille(0)
        val roundTrip = unanswered.toDouble() / total
        return PerMille(((1 - sqrt(1 - roundTrip)) * PER_MILLE).roundToInt())
    }

    /** A window without a round trip of its own takes the delay of the nearest one that has one. */
    private fun fillDelays(windows: List<LinkWindow>): List<LinkWindow> {
        val known = windows.filterIsInstance<LinkWindow.Flowing>().filter { it.delay != UNKNOWN_DELAY }
        return windows.map { window ->
            when {
                window !is LinkWindow.Flowing || window.delay != UNKNOWN_DELAY -> window
                known.isEmpty() -> window.copy(delay = FALLBACK_DELAY)
                else -> window.copy(delay = known.minBy { (it.from - window.from).absoluteValue }.delay)
            }
        }
    }

    /** Adjacent windows that say the same thing are one. */
    private fun merged(windows: List<LinkWindow>): List<LinkWindow> {
        val out = ArrayList<LinkWindow>()
        for (window in windows) {
            val last = out.lastOrNull()
            out +=
                when {
                    last is LinkWindow.Outage && window is LinkWindow.Outage -> LinkWindow.Outage(out.removeLast().from, window.until)
                    last is LinkWindow.Flowing && window is LinkWindow.Flowing && last.sameAs(window) ->
                        window.copy(from = (out.removeLast() as LinkWindow.Flowing).from)
                    else -> window
                }
        }
        return out
    }

    private fun LinkWindow.Flowing.sameAs(other: LinkWindow.Flowing) =
        delay == other.delay && lossToPeer == other.lossToPeer && lossFromPeer == other.lossFromPeer

    private val UNKNOWN_DELAY = DelayRange(Duration.ZERO, Duration.ZERO)
    private const val PER_MILLE = 1000
}
