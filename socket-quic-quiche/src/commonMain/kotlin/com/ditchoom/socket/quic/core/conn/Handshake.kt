package com.ditchoom.socket.quic.core.conn

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.socket.quic.PtoCeiling
import com.ditchoom.socket.quic.QuicResumptionOutcome
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

/** How far the handshake got: still running, or completed (confirmed or not). */
internal enum class Progress { Handshaking, Established }

/** Whether the caller is still to be told the handshake completed: once, at the end of the serve that completed it. */
internal enum class Announcement { Due, None }

private enum class Told { Not, Due, Done }

private sealed interface Phase {
    /** Not confirmed: how far it got. */
    class Pending : Phase {
        var progress = Progress.Handshaking
    }

    data object Confirmed : Phase
}

private sealed interface Deadline {
    data object None : Deadline

    class At(
        val dueNanos: Long,
    ) : Deadline
}

/**
 * The handshake's phase, its PTO ceiling, its deadline and the report of its end. An adaptive rule gives quiche its
 * ceiling once: from the rule's first, 3× with each PTO expiry, back to the first when a packet number space's keys are
 * discarded, and released at the first RTT sample in the application space after confirmation; quiche alone holds that
 * state. The deadline ends the connection only while the handshake is incomplete: once it completes the caller holds
 * the connection, and the idle timeout owns a confirmation that is late.
 */
@Confined
internal class Handshake(
    private val q: QuicheConnRef,
    private val rule: HandshakeRule,
    private val startedNanos: Long,
    private val alpn: NativeBytes,
    private val chain: ChainSource,
    /** The memory the peer's leaf certificate is read into, to be hashed. */
    private val factory: BufferFactory,
) {
    private val pending = Phase.Pending()
    private var phase: Phase = pending

    private var told = Told.Not

    /** Whether the handshake completed and was reported, with what it settled. */
    private var fate: HandshakeFate = HandshakeFate.NotCompleted

    /** quiche's PTO expiries seen while unconfirmed. */
    private var expiries = 0L
    private val deadline: Deadline =
        when (val bound = rule.bound) {
            is HandshakeBound.UntilClosed -> Deadline.None
            is HandshakeBound.Bounded -> Deadline.At(startedNanos + bound.nanos)
        }

    init {
        when (val pto = rule.pto) {
            is PtoRule.Rfc9002 -> Unit
            is PtoRule.Adaptive ->
                q.api.connSetHandshakePtoCeiling(q.handle, PtoCeiling.Growing(pto.firstCeilingNanos, GROWTH.toInt(), MAX_CEILING_NANOS))
        }
    }

    /** [HandshakeFate.Completed] once the completion was reported, which is before any end. */
    fun fate(): HandshakeFate = fate

    /** The completion was reported with [facts], the peer's chain delivered with them. */
    fun reported(facts: HandshakeFacts) {
        fate = HandshakeFate.Completed(facts)
    }

    fun confirmation(): Confirmation =
        when (phase) {
            is Phase.Pending -> Confirmation.Pending
            is Phase.Confirmed -> Confirmation.Confirmed
        }

    /** How far the handshake got as of the last [step]. */
    fun reached(): Progress =
        when (phase) {
            is Phase.Pending -> pending.progress
            is Phase.Confirmed -> Progress.Established
        }

    fun step(now: Nanos): HandshakeStep {
        when (phase) {
            is Phase.Confirmed -> return HandshakeStep.Established
            is Phase.Pending -> Unit
        }
        val was = pending.progress
        if (was == Progress.Handshaking && q.api.connIsEstablished(q.handle)) {
            pending.progress = Progress.Established
            told = Told.Due
        }
        val reached = pending.progress
        val just = if (was == Progress.Handshaking) HandshakeStep.JustEstablished else HandshakeStep.Established
        if (reached == Progress.Established && q.api.connIsHandshakeConfirmed(q.handle)) {
            phase = Phase.Confirmed
            return just
        }
        if (reached == Progress.Handshaking && passed(now)) return HandshakeStep.DeadlinePassed
        return when (reached) {
            Progress.Handshaking -> HandshakeStep.Handshaking
            Progress.Established -> just
        }
    }

    /** [Announcement.Due] once, the first time it is asked after [step] answered [HandshakeStep.JustEstablished]. */
    fun announcement(): Announcement =
        when (told) {
            Told.Due -> {
                told = Told.Done
                Announcement.Due
            }
            Told.Not, Told.Done -> Announcement.None
        }

    /** quiche's timeout ran while the handshake is unconfirmed: its PTO expiries in [read] are counted for the report. */
    fun expired(read: PathRead) {
        when (read) {
            is PathRead.Unread -> Unit
            is PathRead.Read -> expiries = maxOf(expiries, read.probes)
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

    /** The negotiated ALPN, copied into its scratch; its length. */
    fun alpn(): Int = q.api.connApplicationProto(q.handle, alpn.address, alpn.capacity)

    /**
     * Once, after [step] answered [HandshakeStep.JustEstablished], with quiche alive: what the handshake settled is copied
     * out of quiche ([HandshakeFacts], and the peer's chain as [chain] says), for the report. The fate stays
     * [HandshakeFate.NotCompleted] until the report was delivered ([reported]).
     */
    fun report(
        now: Nanos,
        read: PathRead,
        resumption: QuicResumptionOutcome,
    ): ConnectReport {
        val sample = sampleOf(read)
        val probes = probesOf(read)
        val took = now.value - startedNanos
        val peerChain = peerChainOf(chain, q, alpn)
        val facts = HandshakeFacts(alpnText(), resumption, peerLeafOf(peerChain, q, alpn, factory))
        return ConnectReport(
            handshakeNanos = took,
            facts = facts,
            chain = peerChain,
            firstSample = sample,
            priorNanos = rule.priorNanos(),
            link = rule.link,
            probes = probes,
            pace = paceOf(sample, probes, took),
        )
    }

    /** The negotiated ALPN as text: quiche's once the server's EncryptedExtensions were read. */
    fun negotiated(): String = alpnText()

    private fun alpnText(): String {
        val length = alpn()
        val text = alpn.owner
        text.position(0)
        text.setLimit(length)
        return text.readString(length, Charset.UTF8)
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

    private fun passed(now: Nanos): Boolean =
        when (val d = deadline) {
            is Deadline.None -> false
            is Deadline.At -> now.value >= d.dueNanos
        }

    private fun sampleOf(read: PathRead): RttSample =
        when (read) {
            is PathRead.Unread -> RttSample.None
            is PathRead.Read -> read.rtt
        }

    private fun probesOf(read: PathRead): Int =
        when (read) {
            is PathRead.Unread -> expiries.toInt()
            is PathRead.Read -> maxOf(read.probes, expiries).toInt()
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
