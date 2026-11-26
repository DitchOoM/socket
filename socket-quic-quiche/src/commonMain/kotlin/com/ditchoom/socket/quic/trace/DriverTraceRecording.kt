package com.ditchoom.socket.quic.trace

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.socket.quic.MigrationResult
import com.ditchoom.socket.quic.MigrationTrigger
import com.ditchoom.socket.quic.PathKey
import com.ditchoom.socket.quic.SendOutcome
import com.ditchoom.socket.quic.SendTarget
import com.ditchoom.socket.quic.UdpChannel
import com.ditchoom.socket.testkit.trace.TraceEvent
import com.ditchoom.socket.testkit.trace.TraceMigrationOutcome
import com.ditchoom.socket.testkit.trace.TraceMigrationTrigger
import com.ditchoom.socket.udp.DatagramSendException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** Record an auto-migration attempt, what woke it, and how it ended (MIGRATION). */
internal fun QuicTraceRecorder.migrationAttempt(
    trigger: MigrationTrigger,
    attempt: Int,
    outcome: MigrationResult,
) {
    val triggerToken =
        when (trigger) {
            MigrationTrigger.LinkChanged -> TraceMigrationTrigger.LinkChanged
            MigrationTrigger.PathStoppedAnswering -> TraceMigrationTrigger.PathStoppedAnswering
            is MigrationTrigger.StandbyReturnDue -> TraceMigrationTrigger.ReturnFromStandby
        }
    record(TraceEvent.Migration(now(), triggerToken, attempt, outcome.toTraceOutcome()))
}

/**
 * Decorate a [UdpChannel] so every datagram through it is recorded (DGRAM_OUT / DGRAM_IN with
 * [path] as the PathKey, when known) and every non-cancellation IO failure is recorded typed
 * (ERROR) before rethrowing. The driver wraps its per-path channels here — the single
 * platform-neutral choke point of RFC §5.1 item 1.
 */
fun QuicTraceRecorder.wrapUdpChannel(
    channel: UdpChannel,
    path: PathKey? = null,
): UdpChannel = RecordingUdpChannel(channel, this, path)

/**
 * The recording [UdpChannel] decorator: transparent pass-through plus DGRAM_OUT on every
 * successful send, DGRAM_IN on every non-empty receive, and a typed ERROR on IO failure
 * (cancellation passes through unrecorded — it is lifecycle, not a network event).
 */
private class RecordingUdpChannel(
    private val delegate: UdpChannel,
    private val recorder: QuicTraceRecorder,
    private val path: PathKey?,
) : UdpChannel {
    override suspend fun receive(buffer: PlatformBuffer): Int {
        val received =
            try {
                delegate.receive(buffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recorder.error(e)
                throw e
            }
        if (received > 0) recorder.datagram(out = false, buffer = buffer, len = received, path = path)
        return received
    }

    override suspend fun send(
        buffer: PlatformBuffer,
        len: Int,
        target: SendTarget,
    ): SendOutcome {
        // A send now reports its failure instead of raising it, so the recorder branches on the
        // outcome rather than catching. DatagramSendException is constructed only on the failure
        // path, purely to give the recorder the Throwable its trace format takes — the structured
        // reason stays the typed DatagramSendError carried by the outcome.
        val outcome = delegate.send(buffer, len, target)
        return when (outcome) {
            is SendOutcome.Sent -> {
                val destination = (target as? SendTarget.ServerReply)?.to ?: path
                recorder.datagram(out = true, buffer = buffer, len = len, path = destination)
                outcome
            }
            is SendOutcome.Failed -> {
                recorder.error(DatagramSendException(outcome.error))
                outcome
            }
            is SendOutcome.Stalled -> {
                // Unreachable through this decorator: the bound lives in `QuicheDriver.flushOutgoing`
                // and wraps the call to *this* send, so a stalled send never returns through here —
                // the driver records it directly. The branch exists because the outcome is exhaustive
                // by design: a future site that bounds a send *below* the driver is made to decide
                // what the trace says rather than inheriting silence from an `else`.
                recorder.error(SendStalledException(outcome.after))
                outcome
            }
        }
    }

    override fun close() = delegate.close()
}

/**
 * A send that never answered, rendered as a [Throwable] purely so the trace format — which carries
 * errors as throwables — can record one. Never thrown, and never a control-flow signal: the
 * structured value stays [SendOutcome.Stalled].
 */
internal class SendStalledException(
    after: Duration,
) : RuntimeException("send did not answer within $after")

/** Exhaustive by construction: a new [MigrationResult] leaf is a compile error here. */
@Suppress("DEPRECATION") // HandshakeNotConfirmed: never returned, still a leaf.
private fun MigrationResult.toTraceOutcome(): TraceMigrationOutcome =
    when (this) {
        is MigrationResult.Succeeded -> TraceMigrationOutcome.Succeeded
        MigrationResult.Unmoved.Impossible.ServerConnection -> TraceMigrationOutcome.ServerConnection
        MigrationResult.Unmoved.Impossible.PolicyForbids -> TraceMigrationOutcome.PolicyForbids
        MigrationResult.Unmoved.Impossible.PeerForbids -> TraceMigrationOutcome.PeerForbids
        MigrationResult.Unmoved.Impossible.BackendCannotMigrate -> TraceMigrationOutcome.BackendCannotMigrate
        MigrationResult.Unmoved.Impossible.ConnectionClosed -> TraceMigrationOutcome.ConnectionClosed
        MigrationResult.Unmoved.Failed.HandshakeNotConfirmed -> TraceMigrationOutcome.HandshakeNotConfirmed
        MigrationResult.Unmoved.Failed.EndpointNotSelectable -> TraceMigrationOutcome.EndpointNotSelectable
        MigrationResult.Unmoved.Failed.AlreadyInProgress -> TraceMigrationOutcome.AlreadyInProgress
        MigrationResult.Unmoved.Failed.NoSpareConnectionId -> TraceMigrationOutcome.NoSpareConnectionId
        is MigrationResult.Unmoved.Failed.LocalPathUnavailable -> TraceMigrationOutcome.LocalPathUnavailable
        is MigrationResult.Unmoved.Failed.LocalPathOpenTimedOut -> TraceMigrationOutcome.LocalPathOpenTimedOut
        is MigrationResult.Unmoved.Failed.ProbeRejected -> TraceMigrationOutcome.ProbeRejected
        MigrationResult.Unmoved.Failed.PathNotValidated -> TraceMigrationOutcome.PathNotValidated
        is MigrationResult.Unmoved.Failed.SwitchRejected -> TraceMigrationOutcome.SwitchRejected
    }
