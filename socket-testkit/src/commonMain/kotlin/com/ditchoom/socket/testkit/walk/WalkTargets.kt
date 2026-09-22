package com.ditchoom.socket.testkit.walk

import kotlin.time.Duration

/**
 * The address family a walk target's host names, decided by the literal the operator gave.
 *
 * It is decided here rather than asked of the transport because there is nothing to ask: every
 * platform's QUIC builder resolves the hostname inside itself, so `TransportConfig.nameResolution`
 * and the connect racer reach TCP only (#615). A name therefore has no family the probe can name,
 * and that third case is modelled instead of guessed — filing a name's walk under `v4` would
 * attribute its migrations to a family it may never have been on.
 */
public sealed interface AddressFamily {
    /** How every log line names it; the analyzer groups on exactly this token. */
    public val label: String

    public data object V4 : AddressFamily {
        override val label: String = "v4"
    }

    public data object V6 : AddressFamily {
        override val label: String = "v6"
    }

    /** A name, not a literal: whichever family the platform resolver picks at connect time. */
    public data object ResolverChoice : AddressFamily {
        override val label: String = "resolver"
    }

    public companion object {
        /** A host holding ':' is an IPv6 literal, four decimal octets are an IPv4 one, anything else is a name. */
        public fun of(host: String): AddressFamily =
            when {
                host.contains(':') -> V6
                host.isDottedQuad() -> V4
                else -> ResolverChoice
            }
    }
}

private fun String.isDottedQuad(): Boolean {
    val octets = split('.')
    return octets.size == 4 &&
        octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 }
}

/** One server a walk connects to, and the family its host names. */
public data class WalkTarget(
    val host: String,
    val port: Int,
) {
    public val family: AddressFamily = AddressFamily.of(host)

    /** `host:port`, an IPv6 literal bracketed so its own colons cannot be read as the port separator. */
    public val authority: String
        get() =
            when (family) {
                AddressFamily.V6 -> "[$host]:$port"
                AddressFamily.V4, AddressFamily.ResolverChoice -> "$host:$port"
            }

    /** The pair every line naming a target carries. */
    public val line: String get() = "target=$authority family=${family.label}"

    /** The tag one connection's ledger, liveness and verdict lines are reported under. */
    public fun connectionTag(connection: Int): String = "connection=$connection family=${family.label}"
}

/** What a launcher's host argument parsed to. */
public sealed interface WalkTargetsParse {
    public data class Parsed(
        val targets: WalkTargets,
    ) : WalkTargetsParse

    /** The argument named no host at all; a hand-driven probe refuses to invent one. */
    public data object NoHost : WalkTargetsParse
}

/**
 * One lane of a walk: a target it keeps a connection to for the whole run, concurrently with every
 * other lane, so each family is exercised on every network the device crosses.
 *
 * [label] is the target's family, suffixed `-2`, `-3`, … when two targets share one. Every line the
 * lane logs starts with [token], so an analyzer demultiplexes lanes instead of merging them.
 */
public data class WalkLane(
    val label: String,
    val target: WalkTarget,
    val index: Int,
) {
    public val token: String get() = "lane=$label"

    /** [emit] with this lane's [token] in front of every line. */
    public fun log(emit: (String) -> Unit): (String) -> Unit = { emit("$token $it") }

    /** The stem of this lane's [connection]th trace and qlog files: `conn-v6-0003`. */
    public fun fileStem(connection: Int): String = "conn-$label-" + connection.toString().padStart(4, '0')

    /** How long this lane waits before its first connect, so [lanes] lanes send evenly spread across one [interval]. */
    public fun stagger(
        interval: Duration,
        lanes: Int,
    ): Duration = interval * index / lanes

    public companion object {
        /** The token of a line that belongs to the whole run rather than to one lane. */
        public const val RUN_TOKEN: String = "lane=run"
    }
}

/**
 * The targets one walk runs, one [WalkLane] each, all at once: a device on a stable network holds
 * each connection for the whole run, so a single rotating connection would test one family per run.
 *
 * Non-empty by construction: [first] is a field, so a walk with no target cannot be built.
 *
 * A lane never falls back to another family. A probe that retried the other family when a target
 * was unreachable would hide the one thing this exists to measure — a family that does not work on
 * some network — by quietly succeeding on the other. An unreachable target therefore fails its own
 * lane's attempts, is recorded against itself, and that lane backs off and tries again.
 */
public data class WalkTargets(
    val first: WalkTarget,
    val rest: List<WalkTarget> = emptyList(),
) {
    public val all: List<WalkTarget> = listOf(first) + rest

    public val lanes: List<WalkLane> =
        all.mapIndexed { index, target ->
            val family = target.family.label
            val earlier = all.take(index).count { it.family.label == family }
            WalkLane(label = if (earlier == 0) family else "$family-${earlier + 1}", target = target, index = index)
        }

    /** What `START` carries: every target in lane order, each with its family. */
    public val line: String get() = "targets=" + all.joinToString(",") { "${it.authority}/${it.family.label}" }

    /** The run's `LANES` line: each lane's label and target, and how far apart their sends are spread. */
    public fun lanesLine(interval: Duration): String =
        "LANES " + lanes.joinToString(" ") { "${it.label}=${it.target.authority}" } +
            " staggerMs=${(interval / lanes.size).inWholeMilliseconds}"

    override fun toString(): String = line

    public companion object {
        /**
         * Between hosts in a launcher argument. A comma, because an IPv6 literal is made of colons
         * and no hostname or literal may contain a comma.
         */
        public const val SEPARATOR: Char = ','

        public fun parse(
            spec: String,
            port: Int,
        ): WalkTargetsParse {
            val hosts = spec.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return WalkTargetsParse.NoHost
            return WalkTargetsParse.Parsed(
                WalkTargets(WalkTarget(hosts.first(), port), hosts.drop(1).map { WalkTarget(it, port) }),
            )
        }
    }
}
