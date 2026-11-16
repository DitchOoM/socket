package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.testkit.trace.TraceEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Which part of a recording becomes the script. */
internal sealed interface Window {
    /** From [from] to [to] on the recording's clock. */
    data class Between(
        val from: Duration,
        val to: Duration,
    ) : Window

    /**
     * Around the recording's first disruption (an OS reading that changes the network, or an outage): from [lead]
     * before it until [trail] after the last one, which covers an idle timeout.
     */
    data class AroundDisruptions(
        val lead: Duration = 10.seconds,
        val trail: Duration = 45.seconds,
    ) : Window
}

/** What a conversion made. */
internal sealed interface Converted {
    class Script(
        val conditions: Conditions,
        /** OS_NET lines left out because their clock is not the recording's. */
        val unclocked: Int,
    ) : Converted

    class Refused(
        val why: Refusal,
    ) : Converted
}

/**
 * Turns a walk's recording of one connection (the v1 trace grammar: `DGRAM_IN`/`DGRAM_OUT`, `NET`, `OS_NET`,
 * `PATH_STATE`), and the peer's recording of it when there is one, into [Conditions]: the outages and the delay and
 * loss of each window from the datagrams ([LinkEvidence]), and what the OS said and where the route left from
 * ([OsEvidence]). The script starts at the window's start, where the replay opens a fresh connection.
 */
internal object RecordingToConditions {
    fun convert(
        name: String,
        source: String,
        ours: List<TraceEvent>,
        peer: List<TraceEvent> = emptyList(),
        window: Window = Window.AroundDisruptions(),
        idleTimeout: Duration = 30.seconds,
        echoEvery: Duration = 250.milliseconds,
    ): Converted {
        val sorted = ours.sortedBy { it.at }
        val sentOut = sorted.filterIsInstance<TraceEvent.DgramOut>()
        if (sentOut.isEmpty()) return Converted.Refused(Refusal.NoDatagram)
        val family = if (sentOut.first().path?.family == V6) Family.V6 else Family.V4
        val out = sentOut.map { Seen(it.at, it.payloadHex) }
        val received = sorted.filterIsInstance<TraceEvent.DgramIn>().map { Seen(it.at, it.payloadHex) }
        val last = (out.map { it.at } + received.map { it.at }).max()
        val (from, to) =
            when (window) {
                is Window.Between -> window.from to window.to
                is Window.AroundDisruptions -> around(sorted, out, received, family, last, window)
            }
        val timeline =
            when (val read = OsEvidence(family).timeline(sorted, from, to, last)) {
                is OsTimeline.Refused -> return Converted.Refused(read.why)
                is OsTimeline.Read -> read
            }
        val runFor = to - from
        val away = awaySpans(sorted, timeline.bound, from, to)
        val sent = out.inWindow(from, to)
        val got = received.inWindow(from, to)
        val peerSide =
            if (peer.isEmpty()) {
                PeerSide.Unrecorded
            } else {
                aligned(peer, out, from, to)
            }
        val link = LinkEvidence.windows(sent, got, peerSide, timeline.start.network, timeline.events, runFor, away)
        val conditions =
            Conditions(
                name = name,
                source = source,
                family = family,
                evidence = if (peer.isEmpty()) Evidence.ClientOnly else Evidence.BothEnds,
                start = timeline.start,
                echoEvery = echoEvery,
                idleTimeout = idleTimeout,
                link = link,
                os = timeline.events,
                runFor = runFor,
            )
        return Converted.Script(conditions, timeline.unclocked)
    }

    /** The window from [Window.AroundDisruptions.lead] before the first disruption to the trail after the last. */
    private fun around(
        sorted: List<TraceEvent>,
        out: List<Seen>,
        received: List<Seen>,
        family: Family,
        last: Duration,
        window: Window.AroundDisruptions,
    ): Pair<Duration, Duration> {
        val start = out.first().at
        val read = OsEvidence(family).timeline(sorted, start, last, last)
        val whole =
            when (read) {
                is OsTimeline.Refused -> emptyList()
                is OsTimeline.Read -> read.events.map { it.at + start }
            }
        val opened =
            when (read) {
                is OsTimeline.Refused -> NetHandle(NetKind.Other, 1)
                is OsTimeline.Read -> read.start.network
            }
        val silences =
            LinkEvidence
                .windows(out.inWindow(start, last), received.inWindow(start, last), PeerSide.Unrecorded, opened, emptyList(), last - start)
                .filterIsInstance<LinkWindow.Outage>()
                .map { it.from + start }
        val disruptions = (whole + silences).sorted()
        if (disruptions.isEmpty()) return start to last
        val from = maxOf(start, disruptions.first() - window.lead)
        return from to minOf(last + window.trail, disruptions.last() + window.trail)
    }

    /**
     * Where the recorded endpoint had left the bound address (a 4.x lane probing another path and migrating to it),
     * until it migrated back or its probe failed: what it sent and received then says nothing about the bound
     * address's path, which the replayed core, that does not migrate, would have kept using.
     */
    private fun awaySpans(
        sorted: List<TraceEvent>,
        bound: Set<java.net.InetAddress>,
        from: Duration,
        to: Duration,
    ): List<ClosedRange<Duration>> {
        if (bound.isEmpty()) return emptyList()
        val hosts = bound.map { it.hostAddress }.toSet()
        val spans = ArrayList<ClosedRange<Duration>>()
        var awayFrom = Duration.INFINITE
        for (path in sorted.filterIsInstance<TraceEvent.PathState>()) {
            val elsewhere = path.localHost != null && path.localHost !in hosts
            val left = awayFrom != Duration.INFINITE
            val back =
                when (path.phase) {
                    PROBING, MIGRATED -> left && !elsewhere && path.phase == MIGRATED
                    FAILED -> left && !migratedSince(sorted, awayFrom, path.at)
                    else -> false
                }
            if (!left && elsewhere && (path.phase == PROBING || path.phase == MIGRATED)) awayFrom = path.at
            if (back) {
                spans += (awayFrom - from)..(path.at - from)
                awayFrom = Duration.INFINITE
            }
        }
        if (awayFrom != Duration.INFINITE) spans += (awayFrom - from)..(to - from)
        return spans
    }

    private fun migratedSince(
        sorted: List<TraceEvent>,
        since: Duration,
        until: Duration,
    ): Boolean = sorted.any { it is TraceEvent.PathState && it.phase == MIGRATED && it.at >= since && it.at <= until }

    private fun List<Seen>.inWindow(
        from: Duration,
        to: Duration,
    ): List<Seen> = filter { it.at in from..to }.map { Seen(it.at - from, it.hex) }

    /**
     * The peer's datagrams on our clock: the skew is the least that has no datagram of ours arrive before it was
     * sent, as [com.ditchoom.socket.quic.replay.RecordingToSchedule] aligns the two recordings.
     */
    private fun aligned(
        peer: List<TraceEvent>,
        out: List<Seen>,
        from: Duration,
        to: Duration,
    ): PeerSide {
        val peerIn = peer.filterIsInstance<TraceEvent.DgramIn>()
        val arrivedAt = peerIn.groupBy { it.payloadHex }.mapValues { (_, same) -> same.minOf { it.at } }
        val skews = out.filter { it.hex in arrivedAt }.map { it.at - arrivedAt.getValue(it.hex) }
        if (skews.isEmpty()) return PeerSide.Unrecorded
        val skew = skews.max()
        val shift = { at: Duration -> at + skew }
        val received = peerIn.filter { shift(it.at) in from..to }.map { Seen(shift(it.at) - from, it.payloadHex) }
        val sent =
            peer
                .filterIsInstance<TraceEvent.DgramOut>()
                .filter {
                    shift(
                        it.at,
                    ) in from..to
                }.map { Seen(shift(it.at) - from, it.payloadHex) }
        return PeerSide.Recorded(received, sent)
    }

    private const val V6 = 6
    private const val MIGRATED = "Migrated"
    private const val PROBING = "Probing"
    private const val FAILED = "Failed"
}
