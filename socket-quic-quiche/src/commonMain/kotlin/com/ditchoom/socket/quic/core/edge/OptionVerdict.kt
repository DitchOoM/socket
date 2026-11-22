package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.AppleTrustSource
import com.ditchoom.socket.quic.CongestionControl
import com.ditchoom.socket.quic.DatagramMaxAge
import com.ditchoom.socket.quic.DatagramOptions
import com.ditchoom.socket.quic.DatagramOverflow
import com.ditchoom.socket.quic.HandshakePacing
import com.ditchoom.socket.quic.HandshakePolicy
import com.ditchoom.socket.quic.MigrationPolicy
import com.ditchoom.socket.quic.NetworkMonitorSource
import com.ditchoom.socket.quic.Pacing
import com.ditchoom.socket.quic.PinnedRadioRefusal
import com.ditchoom.socket.quic.QuicCloseLinger
import com.ditchoom.socket.quic.QuicOptionRefusal
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicResumption
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.core.conn.MIN_SEND_DEPTH
import com.ditchoom.socket.quic.trace.QlogDeclaration
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.transport.NetworkKind

/** What the core does with a connect's or serve's options: honours all of them, or refuses the first it cannot honour. */
internal sealed interface OptionVerdict {
    data object Honoured : OptionVerdict

    /** A value of an option, refused for [refusal]. */
    class Refused(
        val refusal: QuicOptionRefusal,
    ) : OptionVerdict

    /** A connect pinned to [radio], refused for [refusal]. */
    class PinRefused(
        val radio: NetworkKind.Radio,
        val refusal: PinnedRadioRefusal,
    ) : OptionVerdict
}

/**
 * The core's verdict on the values of [options]: pure, so it runs on the caller's thread before anything is made or opened.
 *
 * Every option with a closed set of values is decided by an exhaustive `when`, so a value added to one cannot compile
 * until it is decided here: honoured, or refused with a [QuicOptionRefusal] that names the option.
 */
internal fun verdictOn(options: QuicOptions): OptionVerdict =
    firstRefused(
        migrationVerdict(options.migration),
        streamOpenVerdict(options.streamOpenPolicy),
        standbyVerdict(options.standbyLink),
        options.trace.let { trace -> if (trace == null) OptionVerdict.Honoured else declaredQlogVerdict(trace.qlog) },
        transportVerdict(options),
    )

private fun firstRefused(vararg verdicts: OptionVerdict): OptionVerdict =
    verdicts.firstOrNull { it !is OptionVerdict.Honoured } ?: OptionVerdict.Honoured

private fun refused(refusal: QuicOptionRefusal) = OptionVerdict.Refused(refusal)

/** The core ends a connection whose network moves, or migrates it; it has no socket pinned to a radio. */
private fun migrationVerdict(policy: MigrationPolicy): OptionVerdict =
    when (policy) {
        is MigrationPolicy.Forbidden, is MigrationPolicy.Manual, is MigrationPolicy.Automatic -> OptionVerdict.Honoured
        is MigrationPolicy.Pinned ->
            OptionVerdict.PinRefused(policy.radio, PinnedRadioRefusal.EngineCannotPin)
    }

/** A stream open waits for the peer's stream credit until the connection ends, or until the policy's bound passes. */
private fun streamOpenVerdict(policy: StreamOpenPolicy): OptionVerdict =
    when (policy) {
        is StreamOpenPolicy.UntilClosed, is StreamOpenPolicy.Bounded -> OptionVerdict.Honoured
    }

/** The core holds no standby link on any platform. */
private fun standbyVerdict(link: StandbyLink): OptionVerdict =
    when (link) {
        is StandbyLink.OnDemand -> OptionVerdict.Honoured
        is StandbyLink.KeepCellularReady -> refused(QuicOptionRefusal.StandbyLinkNotHeld)
    }

/** The core writes a qlog to a file or to the environment's directory; it keeps no budgeted directory of segments. */
internal fun qlogVerdict(target: QlogTarget): OptionVerdict =
    when (target) {
        is QlogTarget.Off, is QlogTarget.File -> OptionVerdict.Honoured
        is QlogTarget.Budgeted -> refused(QuicOptionRefusal.QlogNotBudgeted)
    }

/** What a trace's factory declares of its qlog targets, judged without asking the factory for anything. */
private fun declaredQlogVerdict(declaration: QlogDeclaration): OptionVerdict =
    when (declaration) {
        is QlogDeclaration.NotBudgeted, is QlogDeclaration.Undeclared -> OptionVerdict.Honoured
        is QlogDeclaration.Budgeted -> refused(QuicOptionRefusal.QlogNotBudgeted)
    }

/** The qlog verdict of a connection's [capture]. */
internal fun qlogVerdictOf(capture: TraceCapture): OptionVerdict =
    when (capture) {
        is TraceCapture.Off -> OptionVerdict.Honoured
        is TraceCapture.On -> qlogVerdict(capture.qlog)
    }

/** The options whose every value the core honours, each decided on its own so that a new value is decided too. */
private fun transportVerdict(options: QuicOptions): OptionVerdict {
    when (options.congestionControl) {
        is CongestionControl.Reno, is CongestionControl.Cubic, is CongestionControl.Bbr2 -> Unit
    }
    when (options.pacing) {
        is Pacing.Disabled, is Pacing.Unlimited, is Pacing.Limited -> Unit
    }
    when (options.closeLinger) {
        is QuicCloseLinger.Immediate, is QuicCloseLinger.UntilPeerDone, is QuicCloseLinger.UntilAcknowledged -> Unit
    }
    when (options.networkMonitor) {
        is NetworkMonitorSource.ProcessDefault, is NetworkMonitorSource.Supplied -> Unit
    }
    when (options.resumption) {
        is QuicResumption.None, is QuicResumption.Resume, is QuicResumption.ResumeWithEarlyData -> Unit
    }
    when (options.sessionStore) {
        is QuicSessionStore.NotKept, is QuicSessionStore.InMemory, is QuicSessionStore.Supplied -> Unit
    }
    when (options.handshakePacing) {
        is HandshakePacing.Rfc9002, is HandshakePacing.Adaptive -> Unit
    }
    when (options.handshakePolicy) {
        is HandshakePolicy.Bounded, is HandshakePolicy.UntilClosed -> Unit
    }
    when (options.udpBatching) {
        UdpBatching.Newest, UdpBatching.PublicCallsOnly, UdpBatching.WithoutSegments, UdpBatching.OneDatagramPerCall -> Unit
    }
    when (options.appleTrustSource) {
        is AppleTrustSource.SystemTrustStore, is AppleTrustSource.BundledMozillaRoots -> Unit
    }
    return options.datagrams?.let { datagramVerdict(it) } ?: OptionVerdict.Honoured
}

/**
 * The core's own send and receive queues honour every overflow rule, every maximum age that is positive, and every send
 * depth that holds its hand-off, its door and one queued datagram.
 */
private fun datagramVerdict(options: DatagramOptions): OptionVerdict {
    when (options.overflow) {
        is DatagramOverflow.RefuseNew, is DatagramOverflow.EvictOldest -> Unit
    }
    val depth =
        if (options.sendQueueLen >= MIN_SEND_DEPTH) {
            OptionVerdict.Honoured
        } else {
            refused(QuicOptionRefusal.DatagramSendQueueTooShort(options.sendQueueLen, MIN_SEND_DEPTH))
        }
    return firstRefused(depth, ageVerdict(options.outgoingMaxAge), ageVerdict(options.incomingMaxAge))
}

private fun ageVerdict(age: DatagramMaxAge): OptionVerdict =
    when (age) {
        is DatagramMaxAge.Unbounded -> OptionVerdict.Honoured
        is DatagramMaxAge.Within ->
            if (age.age.isPositive()) OptionVerdict.Honoured else refused(QuicOptionRefusal.DatagramMaxAgeNotPositive(age.age))
    }
