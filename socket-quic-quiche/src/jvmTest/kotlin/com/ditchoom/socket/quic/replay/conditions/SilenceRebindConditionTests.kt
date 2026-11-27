package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.replay.conditions.fixtures.iphoneFlight1031
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A path that stops answering while the OS still calls its link up, on a real core pair on virtual time: every socket
 * the client holds goes dark ([Conditions.strands]), the monitor never moves, and the client's silence watch is the
 * only witness. The silence asks for a fresh port, the connection migrates there through the second socket, and the
 * echo carries on.
 */
class SilenceRebindConditionTests {
    /**
     * The path dies and the monitor says nothing: the connection re-homes on a fresh port, after the silence threshold's
     * four expiries and well inside the 11.5 s the platform's own signal took on the walk. The expiries are counted at
     * the declaration, from the watch's own reads of the active path.
     */
    @Test
    fun aDeadPathReHomesWhileTheMonitorStillCallsTheLinkHealthy() {
        val replayed = ConditionReplay(clean(strands = listOf(STRAND_AT))).run()
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
        assertEquals(1, replayed.moves, "one fresh port: ${replayed.describe()}")
        val silent = replayed.silentAt().single { it > STRAND_AT }
        val rebound = replayed.pathMarks.first { it.phase == "Rebind" }.at
        val expiries = replayed.expiriesAt(silent) - replayed.expiriesAt(STRAND_AT)
        println("[rebind] declared ${silent - STRAND_AT} after the path died, rebound at $rebound, $expiries expiries spent")
        assertEquals(SHIPPED_EXPIRIES, expiries, "the declaration spent $expiries expiries, not the threshold's $SHIPPED_EXPIRIES")
        assertTrue(silent - STRAND_AT < MONITOR_LAG, "declared ${silent - STRAND_AT} after the path died, past the monitor's $MONITOR_LAG")
        assertTrue(replayed.echoes.last().answeredAt > RUN_FOR - 2.seconds, "the echo stopped: ${replayed.describe()}")
    }

    /**
     * A path that dies the moment the connection migrated onto it, before quiche sampled its round trip (so each PTO is
     * the unsampled second): the patience, not the expiry count, declares it, and the connection re-homes again.
     */
    @Test
    fun aPathThatDiesBeforeItsRoundTripIsSampledStillReHomesInTime() {
        val replayed = ConditionReplay(clean(strands = listOf(STRAND_AT)), strandOnPromote = 1).run()
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
        assertEquals(2, replayed.moves, "a fresh port, then another: ${replayed.describe()}")
        val migrated = replayed.pathMarks.first { it.phase == "Migrated" }.at
        val again =
            replayed.pathMarks
                .filter { it.phase == "Rebind" }
                .map { it.at }
                .first { it > migrated }
        println("[rebind] the moved-to path died at $migrated and was rebound ${again - migrated} later")
        assertTrue(again - migrated < MONITOR_LAG, "re-homed ${again - migrated} after the path died: ${replayed.describe()}")
        assertTrue(again - migrated <= PATIENCE + ONE_PTO_UNSAMPLED + SLACK, "re-homed ${again - migrated} after the path died")
    }

    /**
     * The 2026-10-31 flight: two silences of the plane's Wi-Fi and four handoffs after landing, one connection across all
     * of it, never a reconnect, each hop one move.
     */
    @Test
    fun theFlightConnectionCrossesEveryNetworkOfTheTripWithoutAReconnect() {
        val replayed = ConditionReplay(iphoneFlight1031).run()
        val survived = replayed.outcome as? Outcome.Survived ?: throw AssertionError(replayed.describe())
        assertEquals(HOPS, replayed.moves, "each of the $HOPS hops is one move: ${replayed.describe()}")
        assertTrue(survived.longestGap < MONITOR_LAG, "an echo waited ${survived.longestGap}: ${replayed.describe()}")
        assertTrue(replayed.echoes.last().answeredAt > iphoneFlight1031.runFor - 2.seconds, "the echo stopped: ${replayed.describe()}")
    }

    /**
     * The flight says what woke each move: the monitor never moves here, so every move follows a rebind the path's
     * silence asked for, and the declaration that asked for it is recorded too.
     */
    @Test
    fun aDataPlaneMoveRecordsTheSilenceThatWokeIt() {
        val replayed = ConditionReplay(clean(strands = listOf(STRAND_AT, SECOND_STRAND_AT))).run()
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
        val marks = replayed.pathMarks.map { it.phase }.filter { it in setOf("Silent", "Rebind", "Moved") }
        assertEquals(listOf("Silent", "Rebind", "Moved", "Silent", "Rebind", "Moved"), marks, "the flight: ${replayed.pathMarks}")
    }

    /**
     * A 400 ms path that loses half of what crosses it each way, under the walk probe's echo: loss meets the silence
     * threshold again and again, and each silence asks for a fresh port. The ladder outlives each silence, so the path
     * spends fewer ids than one restarting at 250 ms each time did.
     */
    @Test
    fun aPathLosingHalfItsDatagramsSpendsFewerIdsThanARestartingLadderDid() {
        var rebinds = 0
        var silences = 0
        for (seed in LOSSY_SEEDS) {
            val replayed = ConditionReplay(lossy(), seed = seed).run()
            rebinds += replayed.rebinds()
            silences += replayed.silentAt().size
        }
        println("[rebind] half the datagrams lost, seeds $LOSSY_SEEDS: $silences silences, $rebinds rebinds")
        assertTrue(
            rebinds * 4 <= RESTARTING_LADDER_REBINDS * 3,
            "$rebinds rebinds for $silences silences: not a quarter fewer than a restarting ladder's $RESTARTING_LADDER_REBINDS",
        )
    }

    private fun lossy() =
        Conditions(
            name = "A 400 ms path losing half its datagrams after the handshake",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 250.milliseconds,
            idleTimeout = 30.seconds,
            link =
                listOf(
                    LinkWindow.Flowing(Duration.ZERO, LOSSY_AFTER, LOSSY_DELAY, PerMille(0), PerMille(0)),
                    LinkWindow.Flowing(LOSSY_AFTER, LOSSY_RUN, LOSSY_DELAY, PerMille(HALF), PerMille(HALF)),
                ),
            os = emptyList(),
            runFor = LOSSY_RUN,
        )

    /**
     * A roam: Wi-Fi offline for a while and back as the same network, its access point no longer forwarding for the
     * client's old 5-tuple. The path is declared silent while offline, which the edge cannot act on; once the network is
     * back the silence is asked about, the connection moves to a fresh port, and the echo carries on, where dropping
     * the silence left the connection on the dead 5-tuple until its idle end.
     */
    @Test
    fun aSilenceDeclaredDuringARoamMovesOnceTheNetworkIsBack() {
        val wifi = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated)
        val roam = clean(strands = listOf(STRAND_AT)).copy(os = listOf(OsEvent.Offline(STRAND_AT), OsEvent.Up(BACK_AT, wifi)))
        val replayed = ConditionReplay(roam).run()
        val silent = replayed.silentAt().firstOrNull { it > STRAND_AT } ?: throw AssertionError("never silent: ${replayed.describe()}")
        assertTrue(silent < BACK_AT, "precondition: the silence was declared while offline, at $silent")
        assertTrue(replayed.outcome is Outcome.Survived, replayed.describe())
        assertEquals(1, replayed.rebinds(), "one fresh port, once the network was back: ${replayed.describe()}")
        assertTrue(replayed.echoes.last().answeredAt > RUN_FOR - 2.seconds, "the echo stopped: ${replayed.describe()}")
    }

    private fun clean(strands: List<Duration>) =
        Conditions(
            name = "A clean 35 ms link whose sockets go dark",
            source = "synthetic",
            family = Family.V4,
            evidence = Evidence.BothEnds,
            start = NetUp(NetHandle(NetKind.Wifi, 1), Validation.Validated),
            echoEvery = 250.milliseconds,
            idleTimeout = 30.seconds,
            link =
                listOf(
                    LinkWindow.Flowing(Duration.ZERO, RUN_FOR, DelayRange(35.milliseconds, 35.milliseconds), PerMille(0), PerMille(0)),
                ),
            os = emptyList(),
            runFor = RUN_FOR,
            strands = strands,
        )

    /** The active path's expiries as the watch last read them at or before [at]. */
    private fun Replayed.expiriesAt(at: Duration): Long = probes.last { it.at <= at }.expiries

    private companion object {
        val STRAND_AT = 6.seconds
        val SECOND_STRAND_AT = 16.seconds

        /** The roam's network back, after the silence was declared offline. */
        val BACK_AT = 13.seconds
        val RUN_FOR = 26.seconds
        const val SHIPPED_EXPIRIES = 4L
        const val HOPS = 6
        val PATIENCE = 4.seconds

        /** RFC 9002's PTO of a path whose round trip is the unsampled 333 ms, about a second: when the run starts. */
        val ONE_PTO_UNSAMPLED = 1_100.milliseconds
        val SLACK = 300.milliseconds

        val LOSSY_SEEDS = listOf(28L, 38L, 41L, 72L, 95L)
        val LOSSY_DELAY = DelayRange(400.milliseconds, 400.milliseconds)
        val LOSSY_AFTER = 2_500.milliseconds
        val LOSSY_RUN = 120.seconds
        const val HALF = 500

        /** What the same seeds spent with a ladder that started again at 250 ms on every silence: 40, for 40 silences. */
        const val RESTARTING_LADDER_REBINDS = 40

        /** How long the platform took to report the dead Wi-Fi on the walk. */
        val MONITOR_LAG = 11_500.milliseconds
    }
}
