package com.ditchoom.socket.quic

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * RFC 9002 §6.2.2's PTO for a path with no RTT sample: `kInitialRtt` (333 ms) plus four times its
 * initial variance (`kInitialRtt / 2`). RFC 9000 §8.2.4 never sizes a validation timer below it.
 */
internal val K_INITIAL_PTO = 333.milliseconds + (333.milliseconds / 2) * 4

/** One path in the client's quiche path table, with the RFC 9002 §6.2.1 PTO its own samples give. */
internal class PathTimerReading(
    val index: Long,
    val active: Boolean,
    val rtt: Duration,
    val rttvar: Duration,
    val pto: Duration,
) {
    override fun toString(): String = "[$index active=$active srtt=$rtt rttvar=$rttvar pto=$pto]"
}

/**
 * The client's whole path table, each path with its PTO — `srtt + max(4·rttvar, 1 ms) + max_ack_delay`,
 * the formula the driver uses. Quiescent driver only, as every [MigrationSimScope] read is.
 */
internal suspend fun MigrationSimScope.clientPathTimers(): List<PathTimerReading> =
    when (
        val answer =
            clientDriver.inspect { api, conn ->
                val maxAckDelay =
                    when (val params = api.connPeerTransportParams(conn)) {
                        PeerTransportParams.NotYetNegotiated -> Duration.ZERO
                        is PeerTransportParams.Negotiated -> params.maxAckDelayMillis.milliseconds
                    }
                val n = api.connStats(conn)?.pathsCount ?: 0L
                (0 until n).mapNotNull { idx ->
                    api.connPathStats(conn, idx)?.let { st ->
                        PathTimerReading(
                            index = idx,
                            active = st.active,
                            rtt = st.rtt,
                            rttvar = st.rttvar,
                            pto = st.rtt + maxOf(st.rttvar * 4, 1.milliseconds) + maxAckDelay,
                        )
                    }
                }
            }
    ) {
        is Inspected.Read -> answer.value
        Inspected.ConnectionGone -> throw AssertionError("clientPathTimers: the connection is already torn down")
    }

/**
 * [delegate] with quiche's `FailedValidation` path events withheld: a probed path quiche never reports
 * an outcome for, which leaves the driver's RFC 9000 §8.2.4 abandon timer as the only bound on it.
 */
internal class SilentValidationFailureApi(
    private val delegate: QuicheApi,
) : QuicheApi by delegate {
    override fun connPathEventNext(
        conn: QuicheConn,
        localOut: Long,
        localLenOut: Long,
        peerOut: Long,
        peerLenOut: Long,
    ): QuichePathEventType? {
        while (true) {
            val event = delegate.connPathEventNext(conn, localOut, localLenOut, peerOut, peerLenOut)
            if (event != QuichePathEventType.FailedValidation) return event
        }
    }
}

/** [this] env with every quiche path-validation failure withheld — see [SilentValidationFailureApi]. */
internal fun MigrationSimEnv.withSilentValidationFailures(): MigrationSimEnv =
    MigrationSimEnv(SilentValidationFailureApi(api), certChainPath, privKeyPath, codec, randomPin)
