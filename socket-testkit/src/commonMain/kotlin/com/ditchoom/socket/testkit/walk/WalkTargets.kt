package com.ditchoom.socket.testkit.walk

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
    public val family: AddressFamily get() = AddressFamily.of(host)

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
    public data class Rotation(
        val targets: WalkTargets,
    ) : WalkTargetsParse

    /** The argument named no host at all; a hand-driven probe refuses to invent one. */
    public data object NoHost : WalkTargetsParse
}

/**
 * The targets one walk rotates through, one per connection attempt, so a single device covers both
 * address families on one route instead of confounding family with device.
 *
 * Non-empty by construction: [first] is a field, so a walk with no target cannot be built.
 *
 * Rotation is per ATTEMPT and never inside one. A probe that fell back to the other family when a
 * target was unreachable would hide the one thing this exists to measure — a family that does not
 * work on some network — by quietly succeeding on the other. An unreachable target therefore fails
 * its own attempt, is recorded against itself, and the next attempt moves on.
 */
public data class WalkTargets(
    val first: WalkTarget,
    val rest: List<WalkTarget> = emptyList(),
) {
    public val all: List<WalkTarget> get() = listOf(first) + rest

    /** Attempts are 1-based, as the probes count them: attempt 1 takes [first]. */
    public fun forAttempt(attempt: Int): WalkTarget = all[(attempt - 1).mod(all.size)]

    /** What `START` carries: every target in rotation order, each with its family. */
    public val line: String get() = "targets=" + all.joinToString(",") { "${it.authority}/${it.family.label}" }

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
            return WalkTargetsParse.Rotation(
                WalkTargets(WalkTarget(hosts.first(), port), hosts.drop(1).map { WalkTarget(it, port) }),
            )
        }
    }
}
