package com.ditchoom.socket.quic.trace

import kotlin.time.Duration

/**
 * How much of quiche's qlog one directory keeps, and how each connection's record is cut.
 *
 * quiche writes a connection's qlog as a run of segment files of about [segmentBytes] each. A
 * connection keeps at most [connectionSegments] of them — its first, which holds the handshake, both
 * sides' transport parameters and the first CIDs, and its most recent — and the directory keeps at
 * most [directoryBytes] across at most [directoryConnections] connection records. What gives way when
 * a limit is reached is [QlogDirectory]'s to decide, and it reports every loss as a [QlogEvent].
 *
 * Built by [forWalk] from a run's own duration and cadence, or by [of]; both hold the four limits
 * consistent with one another, so a budget with no room for a connection's head and its current
 * segment cannot be constructed.
 */
class QlogBudget private constructor(
    val segmentBytes: Long,
    val connectionSegments: Int,
    val directoryBytes: Long,
    val directoryConnections: Int,
    val origin: QlogBudgetOrigin,
) {
    /** The log line, in the grammar the walk logs are grepped for. */
    val line: String
        get() =
            "QLOG-BUDGET mb=${directoryBytes / MEBIBYTE} segmentKb=${segmentBytes / KIBIBYTE} " +
                "connectionSegments=$connectionSegments connections=$directoryConnections ${origin.line}"

    /**
     * This budget with the directory held to [availableBytes] — for a disk with less room than the
     * plan, where running out would lose qlog writes silently instead of evicting them loudly.
     */
    fun fittedTo(availableBytes: Long): QlogBudget =
        if (availableBytes >= directoryBytes) {
            this
        } else {
            clamped(segmentBytes, connectionSegments, availableBytes, directoryConnections, QlogBudgetOrigin.Fitted(origin, availableBytes))
        }

    override fun toString(): String = line

    companion object {
        private const val KIBIBYTE: Long = 1024L
        private const val MEBIBYTE: Long = 1024L * KIBIBYTE

        /**
         * Bytes of qlog one echo exchange produces at a 250 ms cadence: the highest rate any walk
         * connection has written, client or server side.
         */
        const val BYTES_PER_EXCHANGE: Long = 1_676L

        /** A short run still gets room for its handshakes and a migration or two. */
        const val FLOOR_MB: Long = 256L

        /** Per client: past this, a walk is long enough that its operator should choose the number. */
        const val CEILING_MB: Long = 4_096L

        /** Smaller than a handshake's own record would rotate before the connection said anything. */
        const val MIN_SEGMENT_BYTES: Long = 4L * KIBIBYTE

        private const val MIN_WALK_SEGMENT_BYTES: Long = MEBIBYTE
        private const val MAX_WALK_SEGMENT_BYTES: Long = 64L * MEBIBYTE

        /**
         * The budget for a walk of [minutes] at one echo per [echoInterval], serving [clients] at once.
         *
         * Each client's share is its planned exchanges at [BYTES_PER_EXCHANGE], doubled for what does
         * not scale with the echo loop (handshakes, migrations, loss recovery) — so a walk that goes
         * as planned never evicts anything — and floored and capped per client. A segment is one hour
         * of one client's traffic, so a pull mid-walk collects everything older than an hour, and one
         * connection may fill its client's whole share, because a client's connections are serial and
         * any one of them may span the walk. A client makes at most one connection a minute however
         * often its attempts fail — the probes' reconnect backoff tops out at 60 s — so the directory
         * keeps [minutes] connection records per client.
         */
        fun forWalk(
            minutes: Int,
            echoInterval: Duration,
            clients: Int = 1,
        ): QlogBudget {
            val intervalMs = echoInterval.inWholeMilliseconds.coerceAtLeast(1L)
            val clientCount = clients.coerceAtLeast(1)
            val plannedExchanges = minutes.coerceAtLeast(1).toLong() * 60_000L / intervalMs
            val clientBytes = (plannedExchanges * BYTES_PER_EXCHANGE * 2 / MEBIBYTE).coerceIn(FLOOR_MB, CEILING_MB) * MEBIBYTE
            val segmentBytes = (BYTES_PER_EXCHANGE * (3_600_000L / intervalMs)).coerceIn(MIN_WALK_SEGMENT_BYTES, MAX_WALK_SEGMENT_BYTES)
            return clamped(
                segmentBytes = segmentBytes,
                connectionSegments = ((clientBytes + segmentBytes - 1) / segmentBytes).toInt(),
                directoryBytes = clientBytes * clientCount,
                directoryConnections = minutes.coerceAtLeast(1) * clientCount,
                origin = QlogBudgetOrigin.Walk(minutes, intervalMs, clientCount, plannedExchanges),
            )
        }

        /**
         * A budget from explicit limits, raised where they are inconsistent: a segment of at least
         * [MIN_SEGMENT_BYTES], at least two segments per connection (its head and its current one),
         * a directory with room for both, and room for at least one connection.
         */
        fun of(
            segmentBytes: Long,
            connectionSegments: Int,
            directoryBytes: Long,
            directoryConnections: Int,
        ): QlogBudget = clamped(segmentBytes, connectionSegments, directoryBytes, directoryConnections, QlogBudgetOrigin.Explicit)

        private fun clamped(
            segmentBytes: Long,
            connectionSegments: Int,
            directoryBytes: Long,
            directoryConnections: Int,
            origin: QlogBudgetOrigin,
        ): QlogBudget {
            val segment = segmentBytes.coerceAtLeast(MIN_SEGMENT_BYTES)
            return QlogBudget(
                segmentBytes = segment,
                connectionSegments = connectionSegments.coerceAtLeast(2),
                directoryBytes = directoryBytes.coerceAtLeast(2 * segment),
                directoryConnections = directoryConnections.coerceAtLeast(1),
                origin = origin,
            )
        }
    }
}

/** Where a [QlogBudget]'s numbers came from, so the line that prints them can be checked rather than believed. */
sealed interface QlogBudgetOrigin {
    val line: String

    /** [QlogBudget.forWalk]. */
    data class Walk(
        val minutes: Int,
        val echoIntervalMs: Long,
        val clients: Int,
        val plannedExchanges: Long,
    ) : QlogBudgetOrigin {
        override val line: String
            get() = "walkMinutes=$minutes echoIntervalMs=$echoIntervalMs clients=$clients plannedExchanges=$plannedExchanges"
    }

    /** [QlogBudget.of]. */
    data object Explicit : QlogBudgetOrigin {
        override val line: String get() = "origin=explicit"
    }

    /** [planned], held to the [availableBytes] the disk had. */
    data class Fitted(
        val planned: QlogBudgetOrigin,
        val availableBytes: Long,
    ) : QlogBudgetOrigin {
        override val line: String get() = "${planned.line} fittedToAvailableMb=${availableBytes / (1024L * 1024L)}"
    }
}
