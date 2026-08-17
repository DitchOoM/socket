package com.ditchoom.socket.quic

import com.ditchoom.socket.udp.DatagramSendError
import com.ditchoom.socket.udp.DatagramSendException
import kotlin.coroutines.cancellation.CancellationException

/**
 * What happened to one outbound datagram: it went, or it did not and here is the typed reason.
 *
 * ## Why this is a return value and not an exception
 * [UdpChannel.send] used to signal failure by throwing, which left `QuicheDriver.flushOutgoing` with
 * an untyped `catch (_: Exception)` and therefore exactly one policy for every possible cause —
 * `transitionToClosed()`. That conflated a full kernel send buffer with a vanished network interface
 * and tore the whole connection down for either. Two consequences, both measured:
 *
 * - A transient `ENOBUFS`/`EAGAIN` — backpressure that QUIC would ordinarily ride out by
 *   retransmitting — permanently ended the session, reported as `Closed(error = null)`: the *clean
 *   shutdown* value under the nullable this release replaced. A network fault was indistinguishable
 *   from a peer saying goodbye. (That ambiguity is now gone too — see [QuicCloseReason], where a
 *   close with nothing exchanged is `Unspecified` rather than graceful.)
 * - Active connection migration could never work. A handoff happens *because* the old path died, so
 *   the first send afterwards killed the connection before the new path could be validated.
 *
 * RFC 9000 §10 lists the only three ways a QUIC connection ends — idle timeout, immediate close, and
 * stateless reset. A failed local send is not among them. Making the outcome a sealed type turns
 * "what should happen now?" from a runtime assumption buried in a catch block into a `when` the
 * compiler forces every caller to answer.
 *
 * ## Why the failure carries [DatagramSendError] rather than its own taxonomy
 * `:socket-udp` already models *why* a datagram send failed, across all five backends, and its KDoc
 * already documents the split that matters here (`WouldBlock` is its one transient member). Defining
 * a second transient/terminal split alongside it would put the same knowledge in two places and let
 * them disagree — which is the shape of the defect this type exists to prevent.
 */
sealed interface SendOutcome {
    /** The datagram was handed to the platform for transmission. */
    data object Sent : SendOutcome

    /**
     * The datagram was not transmitted. [error] is the typed reason, never a rendered string.
     *
     * A caller deciding whether to keep using this path reads [error]; it must not infer severity
     * from the mere fact of failure, because most causes are recoverable.
     */
    data class Failed(
        val error: DatagramSendError,
    ) : SendOutcome
}

/**
 * Run a throwing platform send and convert it to a [SendOutcome] — the one place the boundary between
 * `:socket-udp`'s throwing contract and this module's reporting contract is crossed.
 *
 * Shared rather than repeated per backend so the five implementations cannot classify the same failure
 * differently over time. `inline` because this is the per-datagram hot path and a suspend lambda
 * allocation per send is exactly the kind of cost this module exists to avoid.
 *
 * [DatagramSendException] already carries the typed reason, so it is unwrapped rather than re-derived.
 * Anything else is a backend that threw outside its own contract; it becomes
 * [DatagramSendError.Transport], which keeps the cause typed instead of stringifying it. Cancellation
 * propagates untouched — it is lifecycle, not a network event.
 */
internal inline fun sendOutcomeOf(send: () -> Unit): SendOutcome =
    try {
        send()
        SendOutcome.Sent
    } catch (ce: CancellationException) {
        throw ce
    } catch (typed: DatagramSendException) {
        SendOutcome.Failed(typed.error)
    } catch (untyped: Exception) {
        SendOutcome.Failed(DatagramSendError.Transport(untyped))
    }
