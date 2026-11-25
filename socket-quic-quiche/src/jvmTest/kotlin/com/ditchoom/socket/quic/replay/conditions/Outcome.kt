package com.ditchoom.socket.quic.replay.conditions

import com.ditchoom.socket.quic.core.life.ConnEnd
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** One echo the replayed client sent and had answered, on the script's clock. */
internal data class Echo(
    val sentAt: Duration,
    val answeredAt: Duration,
)

/** How long after the network was usable again the first echo was answered. */
internal sealed interface AfterUsable {
    data class Answered(
        val after: Duration,
    ) : AfterUsable

    /** The script has nothing to come back from. */
    data object Undisturbed : AfterUsable

    data object NeverUsable : AfterUsable

    /** Usable again, and no echo answered since. */
    data object Unanswered : AfterUsable
}

/** What became of the replayed connection. */
internal sealed interface Outcome {
    data class Survived(
        val echoes: Int,
        val firstEchoAfterUsable: AfterUsable,
        /** The longest wait between two answered echoes, or from the first echo sent to the first answered. */
        val longestGap: Duration,
    ) : Outcome

    data class Ended(
        val end: KClass<out ConnEnd>,
        /** When it ended, on the script's clock. */
        val at: Duration,
        val echoes: Int,
    ) : Outcome
}

/** A replay's outcome with what it is judged against. */
internal class Replayed(
    val conditions: Conditions,
    val outcome: Outcome,
    val echoes: List<Echo>,
    /** How many times the client's socket was reopened on a new network. */
    val reopens: Int,
    /** How many times the client's socket moved to a new local address. */
    val moves: Int,
    /** When the client sent each datagram, on the script's clock. */
    val clientSends: List<Duration>,
    /** The size of each datagram the client sent, in the order of [clientSends]. */
    val clientSizes: List<Int>,
    /** When the server sent each datagram, on the script's clock. */
    val serverSends: List<Duration>,
    /** The size of each datagram the server sent, in the order of [serverSends]. */
    val serverSizes: List<Int>,
    /** When the client's handshake completed, if it did. */
    val established: List<Duration>,
    /** The path marks of the client's flight recording, on the recording's own clock. */
    val pathMarks: List<PathMarkAt>,
    /** When each [Workload.Download] was read to its end, on the script's clock. */
    val downloaded: List<Duration> = emptyList(),
) {

    /** How long the first move took from its socket moving to its migration, or nothing if it never migrated. */
    fun moveToMigrated(): Duration? {
        val moved = pathMarks.firstOrNull { it.phase == "Moved" } ?: return null
        return pathMarks.firstOrNull { it.phase == "Migrated" && it.at >= moved.at }?.let { it.at - moved.at }
    }

    /** The longest wait between two datagrams the client sent before its handshake completed. */
    fun longestHandshakeSilence(): Duration {
        val until = established.firstOrNull() ?: conditions.runFor
        return (listOf(Duration.ZERO) + clientSends.filter { it < until } + until).zipWithNext { a, b -> b - a }.max()
    }

    fun describe(): String =
        "${conditions.name}: $outcome, reopens=$reopens, moves=$moves, usable again ${conditions.usableAgain()}, " +
            "last echoes ${echoes.takeLast(LAST_ECHOES)}"
}

/** What the replayed client's application does once its connection is up. */
internal sealed interface Workload {
    /** The walk probe's echo, every [Conditions.echoEvery]. */
    data object Echo : Workload

    /** One request, then [bytes] from the server on the same stream, read while nothing of the client's is in flight. */
    data class Download(
        val bytes: Int,
    ) : Workload
}

/** A path mark of the client's flight recording: its [phase] and when, on the recording's clock. */
internal data class PathMarkAt(
    val phase: String,
    val at: Duration,
)

/** What a test expects of a replay. */
internal sealed interface Expect {
    /** The connection lives to the script's end; once usable again its first echo comes back within [firstEchoWithin]. */
    data class Survives(
        val firstEchoWithin: Duration,
        val longestGapAtMost: Duration = Duration.INFINITE,
    ) : Expect

    /** The connection ends as [end], no later than [notAfter] on the script's clock. */
    data class Ends(
        val end: KClass<out ConnEnd>,
        val notAfter: Duration,
    ) : Expect
}

internal fun Replayed.assertMeets(expect: Expect) {
    when (expect) {
        is Expect.Survives ->
            when (val now = outcome) {
                is Outcome.Ended -> throw AssertionError("expected to survive: ${describe()}")
                is Outcome.Survived -> {
                    when (val first = now.firstEchoAfterUsable) {
                        is AfterUsable.Answered ->
                            assertTrue(
                                first.after <= expect.firstEchoWithin,
                                "first echo ${first.after} after usable, over ${expect.firstEchoWithin}: ${describe()}",
                            )
                        AfterUsable.Undisturbed -> Unit
                        AfterUsable.NeverUsable, AfterUsable.Unanswered -> throw AssertionError("no echo after usable: ${describe()}")
                    }
                    assertTrue(now.longestGap <= expect.longestGapAtMost, "an echo gap of ${now.longestGap}: ${describe()}")
                }
            }
        is Expect.Ends ->
            when (val now = outcome) {
                is Outcome.Survived -> throw AssertionError("expected to end as ${expect.end.simpleName}: ${describe()}")
                is Outcome.Ended -> {
                    assertEquals(expect.end, now.end, describe())
                    assertTrue(now.at <= expect.notAfter, "ended at ${now.at}, after ${expect.notAfter}: ${describe()}")
                }
            }
    }
}

/** The outcome of a run that [ended] as given, or lived to the script's end, from its [echoes]. */
internal fun outcomeOf(
    conditions: Conditions,
    echoes: List<Echo>,
    ended: Finish,
): Outcome =
    when (ended) {
        is Finish.Ended -> Outcome.Ended(ended.end::class, ended.at, echoes.size)
        Finish.Lived -> Outcome.Survived(echoes.size, afterUsable(conditions, echoes), longestGap(echoes))
    }

/** How a replay finished. */
internal sealed interface Finish {
    data object Lived : Finish

    class Ended(
        val end: ConnEnd,
        val at: Duration,
    ) : Finish
}

private fun afterUsable(
    conditions: Conditions,
    echoes: List<Echo>,
): AfterUsable =
    when (val usable = conditions.usableAgain()) {
        Usable.Undisturbed -> AfterUsable.Undisturbed
        Usable.Never -> AfterUsable.NeverUsable
        is Usable.At -> {
            val after = echoes.filter { it.answeredAt >= usable.at }
            if (after.isEmpty()) AfterUsable.Unanswered else AfterUsable.Answered(after.first().answeredAt - usable.at)
        }
    }

private fun longestGap(echoes: List<Echo>): Duration {
    if (echoes.isEmpty()) return Duration.ZERO
    val first = echoes.first().answeredAt - echoes.first().sentAt
    return (listOf(first) + echoes.zipWithNext { a, b -> b.answeredAt - a.answeredAt }).max()
}

private const val LAST_ECHOES = 3

/**
 * Replays [conditions] twice, holds the two runs to the same outcome and the same echoes (the script, not the run,
 * decides), and holds the first to [expect]. Returns it for further checks.
 */
internal fun replayTwice(
    conditions: Conditions,
    expect: Expect,
): Replayed {
    val first = ConditionReplay(conditions).run()
    val second = ConditionReplay(conditions).run()
    assertEquals(first.outcome, second.outcome, "the same script ends the same way\n${first.describe()}\n${second.describe()}")
    assertEquals(first.echoes, second.echoes, "the same script echoes at the same instants")
    first.assertMeets(expect)
    return first
}
