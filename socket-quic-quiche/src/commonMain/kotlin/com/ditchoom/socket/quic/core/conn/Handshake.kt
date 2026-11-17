package com.ditchoom.socket.quic.core.conn

import com.ditchoom.socket.quic.PtoCeiling
import com.ditchoom.socket.quic.core.life.HandshakeProgress
import com.ditchoom.socket.quic.core.life.HandshakeReached
import com.ditchoom.socket.quic.core.life.QuicheConnRef
import com.ditchoom.socket.quic.core.life.RttSample
import com.ditchoom.socket.quic.core.observe.Confined
import com.ditchoom.socket.quic.core.path.PathRead
import com.ditchoom.socket.quic.core.time.Nanos
import com.ditchoom.socket.quic.core.time.WakeSink
import com.ditchoom.socket.quic.core.udp.NativeBytes
import kotlin.time.Duration.Companion.nanoseconds

internal enum class HandshakeStep { Handshaking, JustEstablished, Established, DeadlinePassed }

/** Whether the handshake still waits for its confirmation. */
internal enum class Confirmation { Pending, Confirmed }

private enum class Progress { Handshaking, Established }

private sealed interface Phase {
    /** Not confirmed: how far it got, and quiche's PTO expiries so far. */
    class Pending : Phase {
        var progress = Progress.Handshaking
        var expiries = 0L
    }

    data object Confirmed : Phase
}

private sealed interface PtoPace {
    /** quiche's own PTO. */
    data object Rfc9002 : PtoPace

    /** quiche's PTO capped at [ceilingNanos] until confirmation. */
    class Capped : PtoPace {
        var ceilingNanos = 0L
    }
}

private sealed interface Deadline {
    data object None : Deadline

    class At(
        val dueNanos: Long,
    ) : Deadline
}

/**
 * The handshake's phase, its PTO ceiling, its deadline and the report of its end. Until the handshake is confirmed the
 * ceiling starts at the rule's first and grows 3× with each of quiche's PTO expiries; at confirmation the ceiling is
 * removed and quiche's recovery runs unmodified. The deadline ends the connection only while the handshake is
 * incomplete: once it completes the caller holds the connection, and the idle timeout owns a confirmation that is late.
 */
@Confined
internal class Handshake(
    private val q: QuicheConnRef,
    private val rule: HandshakeRule,
    private val startedNanos: Long,
    private val alpn: NativeBytes,
) {
    private val pending = Phase.Pending()
    private var phase: Phase = pending
    private val capped = PtoPace.Capped()
    private val pace: PtoPace =
        when (val pto = rule.pto) {
            is PtoRule.Rfc9002 -> PtoPace.Rfc9002
            is PtoRule.Adaptive -> capped.also { it.ceilingNanos = pto.firstCeilingNanos }
        }
    private val deadline: Deadline =
        when (val bound = rule.bound) {
            is HandshakeBound.UntilClosed -> Deadline.None
            is HandshakeBound.Bounded -> Deadline.At(startedNanos + bound.nanos)
        }

    init {
        when (val p = pace) {
            is PtoPace.Rfc9002 -> Unit
            is PtoPace.Capped -> q.api.connSetHandshakePtoCeiling(q.handle, PtoCeiling.At(p.ceilingNanos))
        }
    }

    fun confirmation(): Confirmation =
        when (phase) {
            is Phase.Pending -> Confirmation.Pending
            is Phase.Confirmed -> Confirmation.Confirmed
        }

    fun step(now: Nanos): HandshakeStep {
        when (phase) {
            is Phase.Confirmed -> return HandshakeStep.Established
            is Phase.Pending -> Unit
        }
        val was = pending.progress
        if (was == Progress.Handshaking && q.api.connIsEstablished(q.handle)) pending.progress = Progress.Established
        val reached = pending.progress
        val just = if (was == Progress.Handshaking) HandshakeStep.JustEstablished else HandshakeStep.Established
        if (reached == Progress.Established && q.api.connIsHandshakeConfirmed(q.handle)) {
            confirm()
            return just
        }
        if (reached == Progress.Handshaking && passed(now)) return HandshakeStep.DeadlinePassed
        return when (reached) {
            Progress.Handshaking -> HandshakeStep.Handshaking
            Progress.Established -> just
        }
    }

    /** quiche's timeout ran while the handshake is unconfirmed: the ceiling follows quiche's PTO expiries in [read]. */
    fun expired(read: PathRead) {
        val expiries =
            when (read) {
                is PathRead.Unread -> return
                is PathRead.Read -> read.probes
            }
        val more = expiries - pending.expiries
        if (more <= 0) return
        pending.expiries = expiries
        when (val p = pace) {
            is PtoPace.Rfc9002 -> Unit
            is PtoPace.Capped -> {
                p.ceilingNanos = grown(p.ceilingNanos, more)
                q.api.connSetHandshakePtoCeiling(q.handle, PtoCeiling.At(p.ceilingNanos))
            }
        }
    }

    /** Offers the deadline while the handshake is incomplete; offers nothing otherwise. */
    fun offerWake(sink: WakeSink) {
        when (pending.progress) {
            Progress.Established -> return
            Progress.Handshaking -> Unit
        }
        when (phase) {
            is Phase.Confirmed -> return
            is Phase.Pending -> Unit
        }
        when (val d = deadline) {
            is Deadline.None -> Unit
            is Deadline.At -> sink.offer(Nanos(d.dueNanos))
        }
    }

    /** Once, after [step] answered [HandshakeStep.JustEstablished]: the negotiated ALPN is copied into its scratch. */
    fun report(
        now: Nanos,
        read: PathRead,
    ): ConnectReport {
        val sample = sampleOf(read)
        val probes = probesOf(read)
        val took = now.value - startedNanos
        return ConnectReport(
            handshakeNanos = took,
            alpnLength = q.api.connApplicationProto(q.handle, alpn.address, alpn.capacity),
            firstSample = sample,
            priorNanos = rule.priorNanos(),
            link = rule.link,
            probes = probes,
            pace = paceOf(sample, probes, took),
        )
    }

    /** Once, after [step] answered [HandshakeStep.DeadlinePassed], so the handshake never completed: what the peer answered. */
    fun progress(
        now: Nanos,
        read: PathRead,
    ): HandshakeProgress =
        HandshakeProgress(
            waited = (now.value - startedNanos).nanoseconds,
            firstSample = sampleOf(read),
            prior = rule.priorNanos().nanoseconds,
            link = rule.link,
            probes = probesOf(read),
            reached =
                when (read) {
                    is PathRead.Unread -> HandshakeReached.NothingReceived
                    is PathRead.Read -> if (read.received > 0) HandshakeReached.PeerAnswered else HandshakeReached.NothingReceived
                },
        )

    private fun confirm() {
        phase = Phase.Confirmed
        when (pace) {
            is PtoPace.Rfc9002 -> Unit
            is PtoPace.Capped -> q.api.connSetHandshakePtoCeiling(q.handle, PtoCeiling.None)
        }
    }

    private fun passed(now: Nanos): Boolean =
        when (val d = deadline) {
            is Deadline.None -> false
            is Deadline.At -> now.value >= d.dueNanos
        }

    /** [ceilingNanos] grown 3× per expiry, [times] times, up to [MAX_CEILING_NANOS]. */
    private fun grown(
        ceilingNanos: Long,
        times: Long,
    ): Long {
        var ceiling = ceilingNanos
        var left = times
        while (left > 0 && ceiling < MAX_CEILING_NANOS) {
            ceiling = minOf(ceiling * GROWTH, MAX_CEILING_NANOS)
            left--
        }
        return ceiling
    }

    private fun sampleOf(read: PathRead): RttSample =
        when (read) {
            is PathRead.Unread -> RttSample.None
            is PathRead.Read -> read.rtt
        }

    private fun probesOf(read: PathRead): Int =
        when (read) {
            is PathRead.Unread -> pending.expiries.toInt()
            is PathRead.Read -> maxOf(read.probes, pending.expiries).toInt()
        }

    private fun paceOf(
        sample: RttSample,
        probes: Int,
        tookNanos: Long,
    ): ConnectPace =
        when (sample) {
            is RttSample.None -> if (probes > 0) ConnectPace.Slow(SlowConnect.ProbesNeeded) else ConnectPace.AsExpected
            is RttSample.Sampled -> {
                val rtt = sample.rtt.inWholeNanoseconds
                when {
                    rtt > GROWTH * rule.priorNanos() -> ConnectPace.Slow(SlowConnect.RttAbovePrior)
                    probes > 0 -> ConnectPace.Slow(SlowConnect.ProbesNeeded)
                    tookNanos > GROWTH * rtt -> ConnectPace.Slow(SlowConnect.PeerSlowToAnswer)
                    else -> ConnectPace.AsExpected
                }
            }
        }

    private companion object {
        const val GROWTH = 3L

        /** An hour: a ceiling past every idle timeout, where growing it further changes nothing. */
        const val MAX_CEILING_NANOS = 3_600_000_000_000L
    }
}
