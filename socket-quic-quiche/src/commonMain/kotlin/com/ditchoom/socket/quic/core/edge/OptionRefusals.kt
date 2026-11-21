package com.ditchoom.socket.quic.core.edge

import com.ditchoom.socket.quic.AppleTrustSource
import com.ditchoom.socket.quic.CongestionControl
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
import com.ditchoom.socket.quic.QuicRole
import com.ditchoom.socket.quic.QuicSessionStore
import com.ditchoom.socket.quic.StandbyLink
import com.ditchoom.socket.quic.StreamOpenPolicy
import com.ditchoom.socket.quic.UdpBatching
import com.ditchoom.socket.quic.trace.QlogTarget
import com.ditchoom.socket.quic.trace.TraceCapture
import com.ditchoom.socket.transport.NetworkKind

/** Whether the platform has a standby radio that [StandbyLink.KeepCellularReady] asks an engine to hold. */
internal enum class StandbyRadio { Present, Absent }

/** This platform's [StandbyRadio]: Android has a cellular radio to hold beside Wi-Fi; no other platform does. */
internal expect val standbyRadioOfThisPlatform: StandbyRadio

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
 * The core's verdict on [options] for a [role], with the connection's [capture] and the platform's [standby] radio.
 *
 * Every option with a closed set of values is decided by an exhaustive `when`, so a value added to one cannot compile
 * until it is decided here: honoured, or refused with a [QuicOptionRefusal] that names the option.
 */
internal fun verdictOn(
    options: QuicOptions,
    role: QuicRole,
    capture: TraceCapture,
    standby: StandbyRadio,
): OptionVerdict =
    firstRefused(
        migrationVerdict(options.migration),
        streamOpenVerdict(options.streamOpenPolicy),
        standbyVerdict(options.standbyLink, options.migration, role, standby),
        qlogVerdict(capture),
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

/** A stream open parks until the peer grants a stream or the connection ends; the caller's own cancellation bounds it. */
private fun streamOpenVerdict(policy: StreamOpenPolicy): OptionVerdict =
    when (policy) {
        is StreamOpenPolicy.UntilClosed -> OptionVerdict.Honoured
        is StreamOpenPolicy.Bounded -> refused(QuicOptionRefusal.StreamOpenNotBounded(policy.deadline))
    }

/** The core holds no standby link; the option is read only by a client under automatic migration. */
private fun standbyVerdict(
    link: StandbyLink,
    migration: MigrationPolicy,
    role: QuicRole,
    standby: StandbyRadio,
): OptionVerdict =
    when (link) {
        is StandbyLink.OnDemand -> OptionVerdict.Honoured
        is StandbyLink.KeepCellularReady ->
            when (role) {
                is QuicRole.Server -> OptionVerdict.Honoured
                is QuicRole.Client ->
                    when (migration) {
                        is MigrationPolicy.Automatic ->
                            when (standby) {
                                StandbyRadio.Present -> refused(QuicOptionRefusal.StandbyRadioNotHeld)
                                StandbyRadio.Absent -> OptionVerdict.Honoured
                            }
                        is MigrationPolicy.Forbidden, is MigrationPolicy.Manual, is MigrationPolicy.Pinned -> OptionVerdict.Honoured
                    }
            }
    }

/** The core writes a qlog to a file or to the environment's directory; it keeps no budgeted directory of segments. */
private fun qlogVerdict(capture: TraceCapture): OptionVerdict =
    when (capture) {
        is TraceCapture.Off -> OptionVerdict.Honoured
        is TraceCapture.On ->
            when (val qlog = capture.qlog) {
                is QlogTarget.Off, is QlogTarget.File -> OptionVerdict.Honoured
                is QlogTarget.Budgeted -> refused(QuicOptionRefusal.QlogNotBudgeted(qlog))
            }
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
    return OptionVerdict.Honoured
}
